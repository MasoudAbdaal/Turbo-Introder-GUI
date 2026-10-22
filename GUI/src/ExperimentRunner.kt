package burp

import http3.GateMode
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean

enum class GuiEngine(val displayName: String) {
    THREADED("THREADED (HTTP/1.1)"),
    BURP("BURP (HTTP/1.1)"),
    BURP2("BURP2 (HTTP/2)"),
    HTTP3("HTTP3 (QUIC)");

    fun prepareRequest(template: String): String = when (this) {
        BURP2 -> template.replaceFirst("HTTP/1.1\r\n", "HTTP/2\r\n")
        THREADED, BURP -> template.replaceFirst("HTTP/2\r\n", "HTTP/1.1\r\n")
        HTTP3 -> template
    }

    override fun toString(): String = displayName
}

interface GuiEngineSession {
    val lastError: String?
    fun start()
    fun queue(
        template: String,
        label: String,
        gate: String?,
        callback: (Request, Boolean) -> Boolean,
    )
    fun openGate(name: String)
    fun complete()
    fun cancel()
}

fun interface GuiEngineSessionFactory {
    fun create(config: EngineConfig, endpoint: String, requestCount: Int): GuiEngineSession
}

class TurboGuiEngineSession(
    private val config: EngineConfig,
    endpoint: String,
    requestCount: Int,
) : GuiEngineSession {
    private val store = ResultStore()
    private val engine: RequestEngine = createEngine(endpoint, requestCount).apply {
        setOutput(store)
    }

    override val lastError: String?
        get() = engine.lastError

    override fun start() = engine.start(5)

    override fun queue(
        template: String,
        label: String,
        gate: String?,
        callback: (Request, Boolean) -> Boolean,
    ) {
        engine.queue(template = template, callback = { request, interesting ->
            store.add(request)
            callback(request, interesting)
        }, gateName = gate, label = label)
    }

    override fun openGate(name: String) = engine.openGate(name)
    override fun complete() = engine.showStats(-1)
    override fun cancel() = engine.cancel()

    private fun createEngine(endpoint: String, requestCount: Int): RequestEngine {
        val concurrency = if (config.technique in setOf(
                ExecutionTechnique.SEQUENTIAL,
                ExecutionTechnique.HTTP2_SINGLE_PACKET,
                ExecutionTechnique.HTTP3_GATE,
            )) 1 else config.concurrentConnections.coerceAtMost(requestCount).coerceAtLeast(1)
        val callback: (Request, Boolean) -> Boolean = { _, _ -> false }
        return when (config.engine) {
            GuiEngine.BURP, GuiEngine.BURP2 -> BurpRequestEngine(
                endpoint, concurrency, config.maxQueueSize, config.retries, 0,
                callback, null, config.engine == GuiEngine.BURP,
            )
            GuiEngine.THREADED -> ThreadedRequestEngine(
                endpoint,
                concurrency,
                config.maxQueueSize,
                if (config.pipeline) config.requestsPerConnection else 1,
                config.requestsPerConnection,
                config.retries,
                0,
                callback,
                config.timeoutSeconds,
                null,
                1024,
                true,
            )
            GuiEngine.HTTP3 -> HTTP3RequestEngine(
                url = endpoint,
                threads = concurrency,
                maxQueueSize = config.maxQueueSize,
                requestsPerConnection = config.requestsPerConnection,
                maxRetriesPerRequest = config.retries,
                callback = callback,
                readCallback = null,
                gateMode = config.http3GateMode,
            )
        }
    }
}

class ExperimentRunner(
    private val config: EngineConfig,
    private val endpoint: String,
    private val batches: List<List<PlannedRequest>>,
    private val onRowChanged: (GuiResult) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onFinished: (String) -> Unit,
    private val sessionFactory: GuiEngineSessionFactory = GuiEngineSessionFactory(::TurboGuiEngineSession),
) : Runnable {
    private val cancelRequested = AtomicBoolean(false)
    private val sessions = CopyOnWriteArraySet<GuiEngineSession>()

    override fun run() {
        var completionMessage = "Completed"
        var terminalError: String? = null
        try {
            config.validationError()?.let { error(it) }
            if (!waitFor(config.startDelayMs)) return
            batches.forEachIndexed { batchIndex, batch ->
                if (cancelRequested.get()) return@forEachIndexed
                onProgress("Running batch ${batchIndex + 1}/${batches.size}")
                runBatch(batchIndex + 1, batch)
                if (batchIndex < batches.lastIndex && !waitFor(config.batchDelayMs)) return@forEachIndexed
            }
            if (cancelRequested.get()) completionMessage = "Cancelled"
        } catch (failure: Throwable) {
            terminalError = failure.message ?: failure.toString()
            completionMessage = "Failed: $terminalError"
        } finally {
            val fallback = terminalError ?: "No response received before the run ended"
            batches.flatten().forEach { plan ->
                if (plan.row.isPending()) {
                    if (cancelRequested.get()) plan.row.cancel() else plan.row.fail(fallback)
                    onRowChanged(plan.row)
                }
                plan.row.releaseEngineRequest()
            }
            sessions.clear()
            onFinished(if (cancelRequested.get()) "Cancelled" else completionMessage)
        }
    }

    private fun runBatch(batchNumber: Int, batch: List<PlannedRequest>) {
        val groups = batch.groupBy { it.endpoint.ifBlank { endpoint } }
        val activeGroups = mutableListOf<ActiveGroup>()
        val activeByTarget = mutableMapOf<String, ActiveGroup>()
        groups.forEach { (target, plans) ->
            if (cancelRequested.get()) return@forEach
            var active: GuiEngineSession? = null
            try {
                active = sessionFactory.create(config, target, plans.size)
                sessions += active
                active.start()
                val group = ActiveGroup(active, plans, mutableListOf())
                activeGroups += group
                activeByTarget[target] = group
            } catch (failure: Throwable) {
                active?.let { runCatching { it.cancel() }; sessions -= it }
                failPending(plans, failure.message ?: failure.toString())
            }
        }
        batch.forEach { plan ->
            if (cancelRequested.get()) return@forEach
            val target = plan.endpoint.ifBlank { endpoint }
            val group = activeByTarget[target] ?: return@forEach
            if (queuePlan(group.session, plan, batchNumber)) group.queuedPlans += plan
            if (gateName(batchNumber, plan) == null && !waitFor(config.requestDelayMs)) return@forEach
        }
        if (cancelRequested.get()) {
            activeGroups.forEach { runCatching { it.session.cancel() } }
        } else {
            val failedSessions = openGates(batchNumber, activeGroups)
            activeGroups.forEach { group ->
                val active = group.session
                val plans = group.plans
                if (active in failedSessions) return@forEach
                try {
                    active.complete()
                    plans.forEach {
                        it.row.refreshMetrics()
                        it.row.releaseEngineRequest()
                        onRowChanged(it.row)
                    }
                } catch (failure: Throwable) {
                    runCatching { active.cancel() }
                    failPending(plans, failure.message ?: failure.toString())
                }
            }
        }
        activeGroups.forEach { group ->
            val active = group.session
            val plans = group.plans
            val error = active.lastError ?: "No response received before batch $batchNumber ended"
            plans.filter { it.row.isPending() }.forEach { plan ->
                if (cancelRequested.get()) plan.row.cancel() else plan.row.fail(error)
                onRowChanged(plan.row)
            }
            sessions -= active
        }
    }

    private fun queuePlan(
        active: GuiEngineSession,
        plan: PlannedRequest,
        batchNumber: Int,
    ): Boolean {
        if (cancelRequested.get()) return false
        plan.row.markQueued()
        onRowChanged(plan.row)
        return try {
            val gate = gateName(batchNumber, plan)
            active.queue(plan.request, plan.label, gate) { request, _ ->
                plan.row.complete(request)
                onRowChanged(plan.row)
                true
            }
            true
        } catch (failure: Throwable) {
            plan.row.fail(failure.message ?: failure.toString())
            onRowChanged(plan.row)
            false
        }
    }

    private fun openGates(
        batchNumber: Int,
        activeGroups: List<ActiveGroup>,
    ): Set<GuiEngineSession> {
        val releases = activeGroups.flatMap { group ->
            group.queuedPlans.groupBy { gateName(batchNumber, it) }.mapNotNull { (gate, grouped) ->
                gate?.let { GateRelease(group.session, it, grouped.first().releaseOffsetMs, grouped) }
            }
        }.sortedBy { it.offsetMs }
        if (releases.isEmpty()) return emptySet()
        val failed = mutableSetOf<GuiEngineSession>()
        val releaseStart = System.nanoTime()
        releases.groupBy { it.offsetMs }.toSortedMap().forEach { (offset, offsetReleases) ->
            if (!waitUntil(releaseStart + offset * 1_000_000L)) return@forEach
            offsetReleases.forEach { release ->
                if (release.session in failed) return@forEach
                try {
                    release.session.openGate(release.gateName)
                    val openedMs = (System.nanoTime() - releaseStart) / 1_000_000L
                    release.plans.forEach { it.row.markGateOpened(openedMs); onRowChanged(it.row) }
                } catch (failure: Throwable) {
                    failed += release.session
                    runCatching { release.session.cancel() }
                    failPending(release.plans, failure.message ?: failure.toString())
                }
            }
        }
        return failed
    }

    private fun failPending(plans: List<PlannedRequest>, message: String) {
        plans.filter { it.row.isPending() }.forEach {
            it.row.fail(message)
            onRowChanged(it.row)
        }
    }

    private fun gateName(batch: Int, plan: PlannedRequest): String? = when (config.technique) {
        ExecutionTechnique.LAST_BYTE -> "gui-batch-$batch-offset-${plan.releaseOffsetMs}"
        ExecutionTechnique.HTTP2_SINGLE_PACKET,
        ExecutionTechnique.HTTP3_GATE -> "gui-batch-$batch"
        else -> null
    }

    private fun waitUntil(deadlineNanos: Long): Boolean {
        while (!cancelRequested.get()) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return true
            LockSupport.parkNanos(minOf(remaining, 2_000_000L))
            if (Thread.interrupted()) return false
        }
        return false
    }

    private fun waitFor(delayMs: Int): Boolean {
        var remaining = delayMs.coerceAtLeast(0)
        while (remaining > 0 && !cancelRequested.get()) {
            val step = minOf(remaining, 100)
            Thread.sleep(step.toLong())
            remaining -= step
        }
        return !cancelRequested.get()
    }

    fun cancel() {
        cancelRequested.set(true)
        sessions.forEach { it.cancel() }
    }

    private data class GateRelease(
        val session: GuiEngineSession,
        val gateName: String,
        val offsetMs: Int,
        val plans: List<PlannedRequest>,
    )

    private data class ActiveGroup(
        val session: GuiEngineSession,
        val plans: List<PlannedRequest>,
        val queuedPlans: MutableList<PlannedRequest>,
    )
}

package burp.gui.research

import burp.*
import http3.GateMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Opt-in local experiment. Production code and engine implementations are unchanged. */
class AbcOrderingResearchTest {
    @Test
    fun `measure server side ABC ordering with the real threaded engine`() {
        val endpoint = System.getenv("TI_ABC_ENDPOINT")
        assumeTrue(!endpoint.isNullOrBlank(), "Set TI_ABC_ENDPOINT to run this local research test")
        val trials = System.getenv("TI_ABC_TRIALS")?.toIntOrNull()?.coerceIn(1, 500) ?: 20
        val enabled = System.getenv("TI_ABC_SCENARIOS")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            ?: setOf("sequential", "three-race", "grouped", "interleaved", "twenty-three-request-batches")
        val log = ClientLog(Path.of(System.getenv("TI_ABC_CLIENT_LOG") ?: "build/abc-order/client.jsonl"))

        repeat(trials) { index ->
            val trial = index + 1
            if ("sequential" in enabled) execute(endpoint!!, "sequential", trial, listOf(listOf('A', 'B', 'C')), false, log)
            if ("three-race" in enabled) execute(endpoint!!, "three-race", trial, listOf(listOf('A', 'B', 'C')), true, log)
            if ("grouped" in enabled) execute(endpoint!!, "grouped", trial, listOf(grouped()), true, log)
            if ("interleaved" in enabled) execute(endpoint!!, "interleaved", trial, listOf(interleaved()), true, log)
            if ("twenty-three-request-batches" in enabled) {
                val miniBatches = (1..20).map { listOf('A', 'B', 'C') }
                execute(endpoint!!, "twenty-three-request-batches", trial, miniBatches, true, log)
            }
        }
    }

    private fun execute(
        endpoint: String,
        scenario: String,
        trial: Int,
        batches: List<List<Char>>,
        gated: Boolean,
        log: ClientLog,
    ) {
        val plans = batches.mapIndexed { batchIndex, types ->
            val experimentId = if (batches.size == 1) {
                "$scenario-${trial.toString().padStart(3, '0')}"
            } else {
                "$scenario-${trial.toString().padStart(3, '0')}-${(batchIndex + 1).toString().padStart(2, '0')}"
            }
            types.mapIndexed { requestIndex, type ->
                plan(endpoint, scenario, experimentId, type, copyNumber(types, requestIndex), requestIndex + 1)
            }
        }
        val allPlans = plans.flatten()
        val maxBatch = batches.maxOf { it.size }
        val config = EngineConfig(
            engine = GuiEngine.THREADED,
            technique = if (gated) ExecutionTechnique.LAST_BYTE else ExecutionTechnique.SEQUENTIAL,
            concurrentConnections = if (gated) maxBatch else 1,
            requestsPerConnection = 1,
            maxQueueSize = 0,
            timeoutSeconds = 10,
            retries = 0,
            pipeline = false,
            http3GateMode = GateMode.AUTO,
        )
        val submitted = mutableListOf<String>()
        val start = System.nanoTime()
        val runner = ExperimentRunner(
            config,
            endpoint,
            plans,
            {},
            {},
            {},
            GuiEngineSessionFactory { actualConfig, actualEndpoint, count ->
                TraceSession(TurboGuiEngineSession(actualConfig, actualEndpoint, count), submitted, log)
            },
        )
        runner.run()
        val duration = System.nanoTime() - start
        assertEquals(allPlans.size, submitted.size, "$scenario queue count")
        allPlans.forEach { plan ->
            val result = plan.row.snapshot()
            assertEquals(GuiResultState.COMPLETED, result.state, "$scenario: ${result.error}")
        }
        log.summary(scenario, trial, allPlans.size, duration, submitted)
    }

    private fun plan(
        endpoint: String,
        scenario: String,
        experimentId: String,
        type: Char,
        copy: Int,
        position: Int,
    ): PlannedRequest {
        val uri = URI(endpoint)
        val host = "${uri.host}:${uri.port}"
        val request = buildString {
            append("POST /race/${type.lowercaseChar()} HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("X-Experiment-ID: $experimentId\r\n")
            append("X-Scenario: $scenario\r\n")
            append("X-Request-Type: $type\r\n")
            append("X-Copy-Number: $copy\r\n")
            append("Content-Length: 1\r\n")
            append("Connection: close\r\n\r\nx")
        }
        val row = GuiResult(position, "$type$copy", request.toByteArray(Charsets.ISO_8859_1), slotId = position.toLong())
        return PlannedRequest(row, request, "$type$copy", endpoint, slotId = position.toLong())
    }

    private fun grouped(): List<Char> = List(20) { 'A' } + List(20) { 'B' } + List(20) { 'C' }

    private fun interleaved(): List<Char> = buildList {
        repeat(20) { add('A'); add('B'); add('C') }
    }

    private fun copyNumber(types: List<Char>, index: Int): Int = types.take(index + 1).count { it == types[index] }

    private class TraceSession(
        private val delegate: GuiEngineSession,
        private val submitted: MutableList<String>,
        private val log: ClientLog,
    ) : GuiEngineSession {
        override val lastError: String? get() = delegate.lastError
        override fun start() = delegate.start()

        override fun queue(template: String, label: String, gate: String?, callback: (Request, Boolean) -> Boolean) {
            val id = header(template, "X-Experiment-ID")
            val type = header(template, "X-Request-Type")
            val copy = header(template, "X-Copy-Number")
            submitted += "$id:$type$copy"
            log.event("queue_submit", id, type, copy, gate)
            delegate.queue(template, label, gate, callback)
        }

        override fun openGate(name: String) {
            log.event("gate_open_requested", name, null, null, name)
            delegate.openGate(name)
            log.event("gate_open_returned", name, null, null, name)
        }

        override fun complete() = delegate.complete()
        override fun cancel() = delegate.cancel()

        private fun header(template: String, name: String): String =
            Regex("(?im)^${Regex.escape(name)}: ([^\\r\\n]+)").find(template)!!.groupValues[1]
    }

    private class ClientLog(private val path: Path) {
        init {
            path.parent?.let(Files::createDirectories)
            Files.deleteIfExists(path)
        }

        @Synchronized
        fun event(event: String, id: String, type: String?, copy: String?, gate: String?) {
            append("{\"event\":\"$event\",\"nano_time\":${System.nanoTime()},\"id\":\"$id\"," +
                "\"type\":${quoted(type)},\"copy\":${quoted(copy)},\"gate\":${quoted(gate)}}")
        }

        @Synchronized
        fun summary(scenario: String, trial: Int, requests: Int, duration: Long, submitted: List<String>) {
            append("{\"event\":\"trial_complete\",\"scenario\":\"$scenario\",\"trial\":$trial," +
                "\"requests\":$requests,\"duration_ns\":$duration,\"completed\":$requests," +
                "\"submitted\":${submitted.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")}}")
        }

        private fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"
        private fun append(line: String) = Files.writeString(
            path, "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }
}

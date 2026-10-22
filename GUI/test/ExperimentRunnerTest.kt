package burp.gui

import burp.*
import http3.GateMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExperimentRunnerTest {
    @Test
    fun `preserves one row per request when queueing fails`() {
        val plans = plans(3)
        val session = FakeSession(failQueueAt = 1)
        runner(plans, session).run()
        assertEquals(3, plans.size)
        assertEquals(GuiResultState.COMPLETED, plans[0].row.snapshot().state)
        assertEquals(GuiResultState.ERROR, plans[1].row.snapshot().state)
        assertEquals(GuiResultState.COMPLETED, plans[2].row.snapshot().state)
    }

    @Test
    fun `marks every untouched row cancelled`() {
        val plans = plans(4)
        val runner = runner(plans, FakeSession())
        runner.cancel()
        runner.run()
        assertTrue(plans.all { it.row.snapshot().state == GuiResultState.CANCELLED })
    }

    @Test
    fun `copies an engine creation failure to every result row`() {
        val plans = plans(2)
        val runner = ExperimentRunner(
            config(), "https://example.com:443", listOf(plans), {}, {}, {},
            GuiEngineSessionFactory { _, _, _ -> error("engine unavailable") },
        )
        runner.run()
        assertTrue(plans.all { it.row.snapshot().state == GuiResultState.ERROR })
        assertTrue(plans.all { it.row.snapshot().error == "engine unavailable" })
    }

    @Test
    fun `opens one real gate for a synchronized batch`() {
        val plans = plans(3)
        val session = FakeSession()
        runner(plans, session, config(ExecutionTechnique.HTTP2_SINGLE_PACKET, GuiEngine.BURP2)).run()
        assertEquals(listOf("gui-batch-1"), session.openedGates)
        assertTrue(session.queuedGates.all { it == "gui-batch-1" })
    }

    @Test
    fun `normalizes request lines for engines`() {
        assertTrue(GuiEngine.BURP.prepareRequest(requestText("HTTP/2")).startsWith("GET / HTTP/1.1"))
        assertTrue(GuiEngine.BURP2.prepareRequest(requestText("HTTP/1.1")).startsWith("GET / HTTP/2"))
    }

    @Test
    fun `uses each imported request target`() {
        val targets = mutableListOf<String>()
        val first = plans(1).single().copy(endpoint = "https://one.example:443")
        val second = plans(1).single().copy(endpoint = "https://two.example:443")
        val runner = ExperimentRunner(
            config(), "", listOf(listOf(first, second)), {}, {}, {},
            GuiEngineSessionFactory { _, endpoint, _ ->
                targets += endpoint
                FakeSession()
            },
        )

        runner.run()

        assertEquals(setOf("https://one.example:443", "https://two.example:443"), targets.toSet())
        assertTrue(listOf(first, second).all { it.row.snapshot().state == GuiResultState.COMPLETED })
    }

    @Test
    fun `groups last byte requests by release offset`() {
        val base = plans(3)
        val plans = listOf(
            base[0].copy(releaseOffsetMs = 0),
            base[1].copy(releaseOffsetMs = 5),
            base[2].copy(releaseOffsetMs = 5),
        )
        val session = FakeSession()
        runner(plans, session, config(ExecutionTechnique.LAST_BYTE, GuiEngine.THREADED)).run()

        assertEquals(
            listOf("gui-batch-1-offset-0", "gui-batch-1-offset-5"),
            session.openedGates,
        )
        assertEquals(
            listOf("gui-batch-1-offset-0", "gui-batch-1-offset-5", "gui-batch-1-offset-5"),
            session.queuedGates,
        )
        assertEquals(0, plans[0].row.snapshot().releaseOffsetMs)
        assertTrue(plans.all { it.row.snapshot().gateOpenedMs != null })
        assertTrue(plans[1].row.snapshot().gateOpenedMs!! >= 5)
    }

    @Test
    fun `zero offsets preserve one last byte gate`() {
        val plans = plans(3)
        val session = FakeSession()

        runner(plans, session, config(ExecutionTechnique.LAST_BYTE, GuiEngine.THREADED)).run()

        assertEquals(listOf("gui-batch-1-offset-0"), session.openedGates)
        assertTrue(session.queuedGates.all { it == "gui-batch-1-offset-0" })
    }

    @Test
    fun `submits interleaved targets in mapping order for every batch`() {
        val submitted = mutableListOf<String>()
        fun plan(number: Int, target: String) = plans(1).single().copy(
            row = GuiResult(number, "Request $number", requestText("HTTP/1.1").toByteArray()),
            label = number.toString(),
            endpoint = target,
        )
        val firstBatch = listOf(plan(1, "http://one:80"), plan(2, "http://two:80"), plan(3, "http://one:80"))
        val secondBatch = listOf(plan(4, "http://one:80"), plan(5, "http://two:80"), plan(6, "http://one:80"))
        val runner = ExperimentRunner(
            config(), "", listOf(firstBatch, secondBatch), {}, {}, {},
            GuiEngineSessionFactory { _, _, _ -> FakeSession(queueLog = submitted) },
        )

        runner.run()

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), submitted)
    }

    private fun runner(
        plans: List<PlannedRequest>,
        session: FakeSession,
        config: EngineConfig = config(),
    ) = ExperimentRunner(
        config, "https://example.com:443", listOf(plans), {}, {}, {},
        GuiEngineSessionFactory { _, _, _ -> session },
    )

    private fun plans(count: Int) = (1..count).map {
        val text = requestText("HTTP/1.1")
        PlannedRequest(GuiResult(it, "Request A", text.toByteArray()), text, "Request A")
    }

    private fun config(
        technique: ExecutionTechnique = ExecutionTechnique.CONCURRENT,
        engine: GuiEngine = GuiEngine.BURP,
    ) = EngineConfig(engine, technique, 10, 100, 100, 10, 0, false, GateMode.AUTO)

    private fun requestText(version: String) = "GET / $version\r\nHost: example.com\r\n\r\n"

    private class FakeSession(
        private val failQueueAt: Int? = null,
        private val queueLog: MutableList<String>? = null,
    ) : GuiEngineSession {
        private var queued = 0
        val openedGates = mutableListOf<String>()
        val queuedGates = mutableListOf<String?>()
        override val lastError: String? = null
        override fun start() = Unit
        override fun queue(
            template: String,
            label: String,
            gate: String?,
            callback: (Request, Boolean) -> Boolean,
        ) {
            val current = queued++
            queuedGates += gate
            queueLog?.add(label)
            if (current == failQueueAt) throw IllegalStateException("queue failed")
            callback(Request(template).apply {
                id = current + 1
                response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
            }, true)
        }
        override fun openGate(name: String) { openedGates += name }
        override fun complete() = Unit
        override fun cancel() = Unit
    }
}

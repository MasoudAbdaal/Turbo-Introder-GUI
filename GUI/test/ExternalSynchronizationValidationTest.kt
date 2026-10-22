package burp.gui

import burp.*
import http3.GateMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ExternalSynchronizationValidationTest {
    @Test
    fun `real external HTTP and HTTPS preserve queue and response association`() {
        val endpoints = listOfNotNull(
            System.getenv("TI_VALIDATION_HTTP_ENDPOINT"),
            System.getenv("TI_VALIDATION_HTTPS_ENDPOINT"),
        )
        assumeTrue(endpoints.isNotEmpty(), "Set a TI_VALIDATION_*_ENDPOINT to run external validation")
        val repeats = System.getenv("TI_VALIDATION_RUNS")?.toIntOrNull()?.coerceAtLeast(1) ?: 20
        val evidencePath = Path.of(System.getenv("TI_VALIDATION_CLIENT_LOG") ?: "build/network-validation/client.jsonl")

        Evidence(evidencePath).use { evidence ->
            endpoints.forEach { endpoint ->
                listOf(
                    Scenario("sequential", ExecutionTechnique.SEQUENTIAL, listOf(0, 0, 0, 0, 0)),
                    Scenario("last-byte", ExecutionTechnique.LAST_BYTE, listOf(0, 0, 0, 0, 0)),
                    Scenario("last-byte-offsets", ExecutionTechnique.LAST_BYTE, listOf(0, 0, 50, 50, 100)),
                ).forEach { scenario ->
                    repeat(repeats) { iteration -> validate(endpoint, scenario, iteration + 1, evidence) }
                }
            }
        }
    }

    private fun validate(endpoint: String, scenario: Scenario, run: Int, evidence: Evidence) {
        val preparationStart = System.nanoTime()
        val plans = plans(endpoint, scenario, run)
        evidence.log("prepared", endpoint, scenario.name, run, null, System.nanoTime(),
            "\"duration_ns\":${System.nanoTime() - preparationStart}")
        val submitted = mutableListOf<String>()
        val config = EngineConfig(
            GuiEngine.THREADED,
            scenario.technique,
            if (scenario.technique == ExecutionTechnique.SEQUENTIAL) 1 else plans.size,
            1,
            20,
            5,
            0,
            false,
            GateMode.AUTO,
        )
        val runner = ExperimentRunner(
            config,
            endpoint,
            listOf(plans),
            { row ->
                if (!row.isPending()) {
                    val snapshot = row.snapshot()
                    evidence.log("terminal", endpoint, scenario.name, run, snapshot.slotId, System.nanoTime(),
                        "\"state\":\"${snapshot.state}\"")
                }
            },
            {},
            {},
            GuiEngineSessionFactory { actualConfig, actualEndpoint, count ->
                TraceSession(
                    TurboGuiEngineSession(actualConfig, actualEndpoint, count),
                    endpoint, scenario.name, run, evidence, submitted,
                )
            },
        )

        runner.run()

        assertEquals((1..5).map { "$it" }, submitted, "$endpoint ${scenario.name} run $run queue order")
        plans.forEachIndexed { index, plan ->
            val snapshot = plan.row.snapshot()
            assertEquals(GuiResultState.COMPLETED, snapshot.state, "${plan.label}: ${snapshot.error}")
            assertEquals((index + 1).toLong(), snapshot.slotId)
            assertTrue(snapshot.response?.toString(Charsets.ISO_8859_1)?.contains(testId(endpoint, scenario, run, index + 1)) == true)
        }
        if (scenario.name == "last-byte-offsets") {
            assertTrue(plans[2].row.snapshot().gateOpenedMs!! >= 50)
            assertTrue(plans[4].row.snapshot().gateOpenedMs!! >= 100)
        }
    }

    private fun plans(endpoint: String, scenario: Scenario, run: Int): List<PlannedRequest> {
        val uri = URI(endpoint)
        val host = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
        return (1..5).map { position ->
            val method = if (position % 2 == 0) "POST" else "GET"
            val body = if (method == "POST") "position=$position" else ""
            val id = testId(endpoint, scenario, run, position)
            val headers = buildString {
                append("$method ${uri.rawPath}/$position HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("X-TI-Test-ID: $id\r\n")
                append("X-TI-Test-Sequence: $position\r\n")
                append("X-TI-Slot-ID: $position\r\n")
                append("X-TI-Test-Batch: ${scenario.name}-$run\r\n")
                append("X-Identity: identity-$position\r\n")
                if (body.isNotEmpty()) append("Content-Length: ${body.toByteArray().size}\r\n")
                append("Connection: close\r\n\r\n$body")
            }
            val offset = scenario.offsets[position - 1]
            val row = GuiResult(
                position, "Request $position", headers.toByteArray(Charsets.ISO_8859_1), run,
                "Identity $position", null, offset, null, position.toLong(),
            )
            PlannedRequest(
                row, headers, "Request $position", endpoint, offset, position.toLong(), 0x00101010 + position,
            )
        }
    }

    private fun testId(endpoint: String, scenario: Scenario, run: Int, position: Int): String =
        "${URI(endpoint).scheme}-${scenario.name}-$run-$position"

    private data class Scenario(
        val name: String,
        val technique: ExecutionTechnique,
        val offsets: List<Int>,
    )

    private class TraceSession(
        private val delegate: GuiEngineSession,
        private val endpoint: String,
        private val scenario: String,
        private val run: Int,
        private val evidence: Evidence,
        private val submitted: MutableList<String>,
    ) : GuiEngineSession {
        override val lastError: String? get() = delegate.lastError
        override fun start() = delegate.start()

        override fun queue(
            template: String,
            label: String,
            gate: String?,
            callback: (Request, Boolean) -> Boolean,
        ) {
            val position = Regex("(?im)^X-TI-Test-Sequence: (\\d+)\\r?$").find(template)!!.groupValues[1]
            submitted += position
            evidence.log("queue_submit", endpoint, scenario, run, position.toLong(), System.nanoTime(),
                "\"gate\":${gate?.let { "\"$it\"" } ?: "null"}")
            delegate.queue(template, label, gate, callback)
        }

        override fun openGate(name: String) {
            evidence.log("gate_open_requested", endpoint, scenario, run, null, System.nanoTime(), "\"gate\":\"$name\"")
            delegate.openGate(name)
            evidence.log("gate_open_returned", endpoint, scenario, run, null, System.nanoTime(), "\"gate\":\"$name\"")
        }

        override fun complete() = delegate.complete()
        override fun cancel() = delegate.cancel()
    }

    private class Evidence(private val path: Path) : Closeable {
        init {
            path.parent?.let(Files::createDirectories)
            Files.deleteIfExists(path)
        }

        @Synchronized
        fun log(
            event: String,
            endpoint: String,
            scenario: String,
            run: Int,
            slotId: Long?,
            nanoTime: Long,
            extra: String,
        ) {
            val line = "{\"event\":\"$event\",\"nano_time\":$nanoTime," +
                "\"endpoint\":\"$endpoint\",\"scenario\":\"$scenario\",\"run\":$run," +
                "\"slot_id\":${slotId ?: "null"},$extra}\n"
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }

        override fun close() = Unit
    }
}

package burp.gui

import burp.*
import http3.GateMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class ThreadedNetworkIntegrationTest {
    @Test
    fun `reordered mapping controls real queue and server sequence across batches`() {
        val desired = listOf(1, 5, 2, 3, 4)
        ProbeServer.http(10).use { server ->
            val service = object : IHttpService {
                override fun getHost() = "127.0.0.1"
                override fun getPort() = server.port
                override fun getProtocol() = "http"
            }
            val model = RequestScenarioModel()
            (1..5).forEach { position ->
                val request = "GET /probe/$position HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${server.port}\r\n" +
                    "X-TI-Test-Sequence: $position\r\nConnection: close\r\n\r\n"
                model.appendTemplate("Request $position", request.toByteArray(), service, null)
            }
            model.moveSlotToInsertion(model.slots.last().id, 1)
            val batches = RequestExecutionPlanner.createBatches(
                model, emptyList(), 2, GuiEngine.THREADED, false,
            )
            val submitted = mutableListOf<Int>()
            val config = EngineConfig(
                GuiEngine.THREADED, ExecutionTechnique.SEQUENTIAL, 1, 100, 20, 5, 0, false, GateMode.AUTO,
            )
            ExperimentRunner(
                config, "", batches, {}, {}, {},
                GuiEngineSessionFactory { actualConfig, endpoint, count ->
                    val delegate = TurboGuiEngineSession(actualConfig, endpoint, count)
                    object : GuiEngineSession by delegate {
                        override fun queue(
                            template: String,
                            label: String,
                            gate: String?,
                            callback: (Request, Boolean) -> Boolean,
                        ) {
                            submitted += Regex("(?im)^X-TI-Test-Sequence: (\\d+)\\r?$")
                                .find(template)!!.groupValues[1].toInt()
                            delegate.queue(template, label, gate, callback)
                        }
                    }
                },
            ).run()

            assertEquals(desired + desired, submitted)
            assertEquals(desired + desired, server.awaitSequences())
            assertEquals(desired.map(Int::toLong), batches[0].map { it.slotId })
            assertEquals(desired.map(Int::toLong), batches[1].map { it.slotId })
        }
    }

    @Test
    fun `real HTTP brute force sends wordlist payloads in sequential order`() {
        ProbeServer.http(5).use { server ->
            val wordlist = Files.createTempFile("turbo-gui-wordlist-", ".txt")
            Files.write(wordlist, (1..5).map(Int::toString), StandardCharsets.UTF_8)
            val marked = "GET /probe HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${server.port}\r\n" +
                "X-TI-Test-Sequence: §seed§\r\n\r\n"
            val target = BruteForceTarget(
                marked.toByteArray(Charsets.ISO_8859_1), "Fuzz", "http://127.0.0.1:${server.port}", null, null,
            )
            val plans = mutableListOf<PlannedRequest>()
            BruteForcePlanner.forEachChunk(target, wordlist, GuiEngine.THREADED) { _, _, chunk -> plans += chunk }
            val config = EngineConfig(
                GuiEngine.THREADED, ExecutionTechnique.SEQUENTIAL, 1, 100, 20, 5, 0, false, GateMode.AUTO,
            )

            ExperimentRunner(config, target.endpoint, listOf(plans), {}, {}, {}).run()

            assertEquals((1..5).toList(), server.awaitSequences())
            assertEquals((1..5).map(Int::toString), plans.map { it.row.snapshot().payload })
            assertEquals((1..5).toList(), plans.completedSequenceNumbers())
            Files.deleteIfExists(wordlist)
        }
    }

    @Test
    fun `real HTTP sequential and concurrent requests keep their sequence identity`() {
        ProbeServer.http(6).use { sequential ->
            val outcome = runScenario(sequential, ExecutionTechnique.SEQUENTIAL, 6)
            val observed = sequential.awaitSequences()
            println("HTTP sequential server arrival: $observed")
            assertEquals((1..6).toList(), observed)
            assertEquals((1..6).toList(), outcome.completedSequenceNumbers())
        }

        ProbeServer.http(12).use { concurrent ->
            val outcome = runScenario(concurrent, ExecutionTechnique.CONCURRENT, 12)
            val observed = concurrent.awaitSequences()
            println("HTTP concurrent server arrival: $observed")
            assertEquals((1..12).toSet(), observed.toSet())
            assertEquals((1..12).toList(), outcome.completedSequenceNumbers())
        }
    }

    @Test
    fun `real HTTP last byte gate completes every mapped request`() {
        ProbeServer.http(8).use { server ->
            val outcome = runScenario(server, ExecutionTechnique.LAST_BYTE, 8)
            val observed = server.awaitSequences()
            println("HTTP last-byte server arrival: $observed")
            assertEquals((1..8).toSet(), observed.toSet())
            assertTrue(outcome.all { it.row.snapshot().gateOpenedMs != null })
            assertEquals((1..8).toList(), outcome.completedSequenceNumbers())
        }
    }

    @Test
    fun `real HTTPS sequential and concurrent requests keep their sequence identity`() {
        ProbeServer.https(5).use { sequential ->
            val outcome = runScenario(sequential, ExecutionTechnique.SEQUENTIAL, 5)
            val observed = sequential.awaitSequences()
            println("HTTPS sequential server arrival: $observed")
            assertEquals((1..5).toList(), observed)
            assertEquals((1..5).toList(), outcome.completedSequenceNumbers())
        }

        ProbeServer.https(10).use { concurrent ->
            val outcome = runScenario(concurrent, ExecutionTechnique.CONCURRENT, 10)
            val observed = concurrent.awaitSequences()
            println("HTTPS concurrent server arrival: $observed")
            assertEquals((1..10).toSet(), observed.toSet())
            assertEquals((1..10).toList(), outcome.completedSequenceNumbers())
        }
    }

    private fun runScenario(
        server: ProbeServer,
        technique: ExecutionTechnique,
        count: Int,
    ): List<PlannedRequest> {
        val plans = (1..count).map { sequence ->
            val request = "GET /probe HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${server.port}\r\n" +
                "X-TI-Test-Sequence: $sequence\r\n\r\n"
            PlannedRequest(
                row = GuiResult(sequence, "Slot $sequence", request.toByteArray()),
                request = request,
                label = "Slot $sequence",
                endpoint = "${server.scheme}://127.0.0.1:${server.port}",
            )
        }
        val config = EngineConfig(
            GuiEngine.THREADED,
            technique,
            concurrentConnections = count,
            requestsPerConnection = 100,
            maxQueueSize = count + 10,
            timeoutSeconds = 5,
            retries = 0,
            pipeline = false,
            http3GateMode = GateMode.AUTO,
        )
        ExperimentRunner(config, "", listOf(plans), {}, {}, {}).run()
        return plans
    }

    private fun List<PlannedRequest>.completedSequenceNumbers(): List<Int> {
        forEach { assertEquals(GuiResultState.COMPLETED, it.row.snapshot().state, it.row.snapshot().error) }
        return map { plan ->
            val response = plan.row.snapshot().response!!.toString(Charsets.ISO_8859_1)
            Regex("X-Probe-Sequence: (\\d+)").find(response)!!.groupValues[1].toInt()
        }
    }

    private class ProbeServer private constructor(
        val scheme: String,
        private val server: ServerSocket,
        private val expected: Int,
    ) : AutoCloseable {
        val port: Int = server.localPort
        private val running = AtomicBoolean(true)
        private val completed = CountDownLatch(expected)
        private val sequences = Collections.synchronizedList(mutableListOf<Int>())
        private val clients = Collections.synchronizedList(mutableListOf<Socket>())
        private val acceptThread = Thread({ acceptLoop() }, "gui-probe-accept").apply {
            isDaemon = true
            start()
        }

        fun awaitSequences(): List<Int> {
            assertTrue(completed.await(10, TimeUnit.SECONDS), "Probe server did not receive $expected requests")
            return synchronized(sequences) { sequences.toList() }
        }

        private fun acceptLoop() {
            while (running.get() && completed.count > 0) {
                try {
                    val socket = server.accept()
                    clients += socket
                    Thread({ handle(socket) }, "gui-probe-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: SocketException) {
                    return
                }
            }
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                while (running.get() && completed.count > 0) {
                    val request = readHeaders(input) ?: return
                    val sequence = Regex("(?im)^X-TI-Test-Sequence:\\s*(\\d+)\\s*$")
                        .find(request)?.groupValues?.get(1)?.toInt() ?: return
                    sequences += sequence
                    val body = "OK-$sequence"
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: ${body.length}\r\n" +
                        "X-Probe-Sequence: $sequence\r\n" +
                        "Connection: keep-alive\r\n\r\n$body"
                    output.write(response.toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                    completed.countDown()
                }
            } catch (_: Exception) {
                // Runner assertions expose missing requests or transport errors.
            } finally {
                runCatching { socket.close() }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            synchronized(clients) { clients.forEach { runCatching { it.close() } } }
            acceptThread.join(1_000)
        }

        private fun readHeaders(input: java.io.InputStream): String? {
            val data = ByteArrayOutputStream()
            var matched = 0
            val marker = byteArrayOf(13, 10, 13, 10)
            while (data.size() < 128 * 1024) {
                val value = input.read()
                if (value < 0) return null
                data.write(value)
                matched = if (value.toByte() == marker[matched]) matched + 1 else if (value == 13) 1 else 0
                if (matched == marker.size) return data.toString(Charsets.ISO_8859_1)
            }
            return null
        }

        companion object {
            fun http(expected: Int) = ProbeServer("http", ServerSocket(0), expected)

            fun https(expected: Int): ProbeServer {
                val password = "changeit".toCharArray()
                val directory = Files.createTempDirectory("turbo-gui-tls-")
                val keyStorePath = directory.resolve("probe.p12")
                val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
                val keytool = java.io.File(System.getProperty("java.home"), "bin/$executable").absolutePath
                val process = ProcessBuilder(
                    keytool, "-genkeypair", "-alias", "probe", "-keyalg", "RSA",
                    "-storetype", "PKCS12", "-keystore", keyStorePath.toString(),
                    "-storepass", String(password), "-keypass", String(password),
                    "-dname", "CN=localhost", "-validity", "1",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "keytool failed: $output" }
                val keyStore = KeyStore.getInstance("PKCS12")
                Files.newInputStream(keyStorePath).use { keyStore.load(it, password) }
                val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagers.init(keyStore, password)
                val context = SSLContext.getInstance("TLS")
                context.init(keyManagers.keyManagers, null, null)
                val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
                return ProbeServer("https", socket, expected)
            }
        }
    }
}

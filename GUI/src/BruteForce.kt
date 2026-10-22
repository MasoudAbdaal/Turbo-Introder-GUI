package burp

import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

data class BruteForceTarget(
    val request: ByteArray,
    val label: String,
    val endpoint: String,
    val identity: IdentityConfig?,
    val service: IHttpService?,
)

object PayloadPositions {
    const val MARKER = "§"
    private val markerByte = MARKER.toByteArray(Charsets.ISO_8859_1).single()

    fun add(request: ByteArray, selection: IntArray): ByteArray {
        require(selection.size >= 2 && selection[0] in 0 until selection[1] && selection[1] <= request.size) {
            "Select request text before adding a payload position."
        }
        require(!hasPosition(request)) { "Clear the existing payload position before adding another." }
        val output = ByteArrayOutputStream(request.size + 2)
        output.write(request, 0, selection[0])
        output.write(markerByte.toInt())
        output.write(request, selection[0], selection[1] - selection[0])
        output.write(markerByte.toInt())
        output.write(request, selection[1], request.size - selection[1])
        return output.toByteArray()
    }

    fun clear(request: ByteArray): ByteArray = request.filter { it != markerByte }.toByteArray()

    fun hasPosition(request: ByteArray): Boolean = request.count { it == markerByte } >= 2

    fun replace(request: ByteArray, payload: String): ByteArray {
        val markers = request.indices.filter { request[it] == markerByte }
        require(markers.isNotEmpty() && markers.size % 2 == 0) {
            "Add a complete §payload§ position to the selected request."
        }
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream(request.size + payloadBytes.size)
        var cursor = 0
        markers.chunked(2).forEach { pair ->
            output.write(request, cursor, pair[0] - cursor)
            output.write(payloadBytes)
            cursor = pair[1] + 1
        }
        output.write(request, cursor, request.size - cursor)
        return output.toByteArray()
    }
}

object BruteForcePlanner {
    const val CHUNK_SIZE = 1_000

    fun forEachChunk(
        target: BruteForceTarget,
        wordlist: Path,
        engine: GuiEngine,
        cancelled: () -> Boolean = { false },
        consume: (chunkNumber: Int, processedCount: Int, plans: List<PlannedRequest>) -> Unit,
    ) {
        require(Files.isRegularFile(wordlist) && Files.isReadable(wordlist)) {
            "The selected wordlist is missing or unreadable."
        }
        require(PayloadPositions.hasPosition(target.request)) {
            "Add a payload position to the selected request."
        }
        var processed = 0
        var chunkNumber = 0
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        Files.newInputStream(wordlist).use { input ->
            InputStreamReader(input, decoder).buffered().use { reader ->
                while (true) {
                    if (cancelled()) throw CancellationException("Brute Force preparation cancelled")
                    val plans = ArrayList<PlannedRequest>(CHUNK_SIZE)
                    while (plans.size < CHUNK_SIZE) {
                        if (cancelled()) throw CancellationException("Brute Force preparation cancelled")
                        val payload = reader.readLine() ?: break
                        val requestNumber = processed + plans.size + 1
                        plans += createPlan(target, engine, payload, requestNumber)
                    }
                    if (plans.isEmpty()) break
                    processed += plans.size
                    chunkNumber++
                    consume(chunkNumber, processed, plans)
                }
            }
        }
        require(processed > 0) { "The selected wordlist is empty." }
    }

    private fun createPlan(
        target: BruteForceTarget,
        engine: GuiEngine,
        payload: String,
        requestNumber: Int,
    ): PlannedRequest {
        val replaced = PayloadPositions.replace(target.request, payload)
        val applied = IdentityHeaders.apply(replaced, target.identity)
        val prepared = engine.prepareRequest(String(applied, Charsets.ISO_8859_1))
        val row = GuiResult(
            requestNumber,
            target.label,
            prepared.toByteArray(Charsets.ISO_8859_1),
            1,
            target.identity?.name ?: "Original",
            target.service,
            0,
            payload,
        )
        return PlannedRequest(row, prepared, target.label, target.endpoint)
    }
}

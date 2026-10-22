package burp.gui

import burp.*
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class BruteForceTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `payload markers wrap selection replace bytes and clear cleanly`() {
        val original = bytes("username=admin&password=seed")
        val start = text(original).indexOf("seed")
        val marked = PayloadPositions.add(original, intArrayOf(start, start + 4))

        assertTrue(text(marked).contains("password=${PayloadPositions.MARKER}seed${PayloadPositions.MARKER}"))
        assertEquals("username=admin&password=test123", text(PayloadPositions.replace(marked, "test123")))
        assertArrayEquals(original, PayloadPositions.clear(marked))
        val unicode = PayloadPositions.replace(marked, "päss")
        assertFalse(text(unicode).contains(PayloadPositions.MARKER))
        assertArrayEquals("päss".toByteArray(StandardCharsets.UTF_8), unicode.takeLast(5).toByteArray())
    }

    @Test
    fun `planner streams wordlist in order and preserves payload association`() {
        val wordlist = temporary.resolve("words.txt")
        Files.write(wordlist, listOf("first", "second", "third"), StandardCharsets.UTF_8)
        val request = markedRequest(
            "POST / HTTP/1.1\r\nHost: localhost\r\nAuthorization: old\r\nContent-Length: 8\r\n\r\nkey=", "x",
        )
        val target = BruteForceTarget(
            request, "Login", "http://localhost:80", IdentityConfig("User", "Authorization: Bearer new"), null,
        )

        val plans = collectPlans(target, wordlist)

        assertEquals(listOf("first", "second", "third"), plans.map { it.row.snapshot().payload })
        assertTrue(plans[1].request.endsWith("key=second"))
        assertTrue(plans[1].request.contains("Authorization: Bearer new"))
        assertEquals("User", plans[1].row.snapshot().identity)
        assertFalse(plans.any { it.request.contains(PayloadPositions.MARKER) })
        assertTrue(Request(plans[1].request).getRequest().contains("Content-Length: 10"))
    }

    @Test
    fun `planner uses exact thousand entry boundaries without gaps or duplicates`() {
        listOf(100, 1_000, 1_001, 10_000, 100_000).forEach { size ->
            val wordlist = temporary.resolve("words-$size.txt")
            Files.newBufferedWriter(wordlist, StandardCharsets.UTF_8).use { writer ->
                (1..size).forEach { writer.appendLine("payload-$it") }
            }
            var next = 1
            val chunkSizes = mutableListOf<Int>()

            BruteForcePlanner.forEachChunk(target(), wordlist, GuiEngine.THREADED) {
                    chunkNumber, processed, plans ->
                assertEquals(chunkSizes.size + 1, chunkNumber)
                assertTrue(plans.size <= BruteForcePlanner.CHUNK_SIZE)
                plans.forEach { plan ->
                    assertEquals("payload-$next", plan.row.snapshot().payload)
                    assertTrue(plan.request.contains("/payload-$next "))
                    next++
                }
                chunkSizes += plans.size
                assertEquals(next - 1, processed)
            }

            assertEquals(size + 1, next)
            assertEquals((size + 999) / 1_000, chunkSizes.size)
            assertEquals(if (size % 1_000 == 0) 1_000 else size % 1_000, chunkSizes.last())
        }
    }

    @Test
    fun `planner stops between chunks and closes the wordlist on cancellation`() {
        val wordlist = temporary.resolve("many.txt")
        Files.write(wordlist, (1..1_500).map(Int::toString), StandardCharsets.UTF_8)
        val cancelled = AtomicBoolean()

        assertThrows(CancellationException::class.java) {
            BruteForcePlanner.forEachChunk(target(), wordlist, GuiEngine.THREADED, cancelled::get) { _, _, plans ->
                assertEquals(1_000, plans.size)
                cancelled.set(true)
            }
        }
        Files.delete(wordlist)
        assertFalse(Files.exists(wordlist))
    }

    @Test
    fun `planner rejects empty and malformed UTF-8 wordlists`() {
        val empty = temporary.resolve("empty.txt")
        Files.createFile(empty)
        assertThrows(IllegalArgumentException::class.java) {
            BruteForcePlanner.forEachChunk(target(), empty, GuiEngine.THREADED) { _, _, _ -> }
        }
        val malformed = temporary.resolve("malformed.txt")
        Files.write(malformed, byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(MalformedInputException::class.java) {
            BruteForcePlanner.forEachChunk(target(), malformed, GuiEngine.THREADED) { _, _, _ -> }
        }
    }

    private fun collectPlans(target: BruteForceTarget, wordlist: Path): List<PlannedRequest> {
        val plans = mutableListOf<PlannedRequest>()
        BruteForcePlanner.forEachChunk(target, wordlist, GuiEngine.THREADED) { _, _, chunk -> plans += chunk }
        return plans
    }

    private fun target() = BruteForceTarget(
        bytes("GET /${PayloadPositions.MARKER}x${PayloadPositions.MARKER} HTTP/1.1\r\n" +
            "Host: localhost\r\n\r\n"),
        "Fuzz", "http://localhost:80", null, null,
    )

    private fun markedRequest(prefix: String, selected: String): ByteArray =
        bytes(prefix + PayloadPositions.MARKER + selected + PayloadPositions.MARKER)

    private fun bytes(value: String) = value.toByteArray(Charsets.ISO_8859_1)
    private fun text(value: ByteArray) = value.toString(Charsets.ISO_8859_1)
}

package burp.gui

import burp.GuiResult
import burp.GuiResultState
import burp.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GuiResultTest {
    @Test
    fun `tracks a successful request from planned to completed`() {
        val result = GuiResult(1, "Request A", requestBytes())
        result.markQueued()

        val request = Request(requestText()).apply {
            id = 17
            response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
            ttfb = 120
            ttlb = 180
            connectionId = "connection-1"
        }
        result.complete(request)

        val snapshot = result.snapshot()
        assertEquals(GuiResultState.COMPLETED, snapshot.state)
        assertEquals(17, snapshot.queueId)
        assertEquals(200, snapshot.status)
        assertEquals(120, snapshot.ttfb)
        assertEquals(180, snapshot.ttlb)
        assertEquals("connection-1", snapshot.connectionId)
        assertNull(snapshot.error)
    }

    @Test
    fun `turns a missing response into an error row`() {
        val result = GuiResult(1, "Request A", requestBytes())
        val request = Request(requestText()).apply {
            id = 3
            response = "The server closed the connection without issuing a response."
        }

        result.complete(request)

        assertEquals(GuiResultState.ERROR, result.snapshot().state)
        assertEquals(0, result.snapshot().status)
    }

    @Test
    fun `keeps the submitted request when execution fails before a callback`() {
        val submitted = requestBytes()
        val result = GuiResult(1, "Request A", submitted)
        result.markQueued()
        result.fail("connection refused")

        val snapshot = result.snapshot()
        assertEquals(GuiResultState.ERROR, snapshot.state)
        assertEquals("connection refused", snapshot.error)
        assertEquals(requestText(), String(snapshot.request, Charsets.ISO_8859_1))
    }

    private fun requestText() = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"

    private fun requestBytes() = requestText().toByteArray(Charsets.ISO_8859_1)
}

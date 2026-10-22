package burp.gui

import burp.ExtractionDefinition
import burp.ExtractionType
import burp.ResponseExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResponseExtractorTest {
    private val response = ("HTTP/1.1 200 OK\r\nX-Request-ID: header-7\r\n" +
        "Content-Type: application/json\r\n\r\n" +
        "{\"success\":true,\"data\":{\"requestId\":\"ABC-123\",\"balance\":500}}")
        .toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `extracts nested JSON values`() {
        assertEquals("ABC-123", extract(ExtractionType.JSON, "data.requestId"))
        assertEquals("500", extract(ExtractionType.JSON, "data.balance"))
    }

    @Test
    fun `extracts response headers case insensitively`() {
        assertEquals("header-7", extract(ExtractionType.HEADER, "x-request-id"))
    }

    @Test
    fun `uses the first regex capture group`() {
        assertEquals("ABC-123", extract(ExtractionType.REGEX, "requestId\\\":\\\"([^\\\"]+)"))
    }

    @Test
    fun `invalid input yields an empty value`() {
        assertEquals("", extract(ExtractionType.JSON, "missing.value"))
        assertEquals("", extract(ExtractionType.REGEX, "["))
    }

    private fun extract(type: ExtractionType, expression: String) =
        ResponseExtractor.extract(ExtractionDefinition("value", type, expression), response)
}

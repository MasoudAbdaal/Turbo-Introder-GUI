package burp.gui

import burp.IdentityConfig
import burp.IdentityHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityHeadersTest {
    @Test
    fun `replaces duplicate auth headers and preserves body and unrelated headers`() {
        val request = ("POST /pay HTTP/1.1\r\nHost: example.com\r\nCookie: old=1\r\n" +
            "cookie: duplicate=1\r\nAuthorization: Bearer old\r\nX-Test: keep\r\n\r\namount=10")
            .toByteArray(Charsets.ISO_8859_1)
        val result = String(
            IdentityHeaders.apply(
                request,
                IdentityConfig("User A", "Cookie: session=new\nAuthorization: Bearer new"),
            ),
            Charsets.ISO_8859_1,
        )
        assertEquals(1, Regex("(?im)^Cookie:").findAll(result).count())
        assertEquals(1, Regex("(?im)^Authorization:").findAll(result).count())
        assertTrue(result.contains("X-Test: keep"))
        assertTrue(result.endsWith("amount=10"))
    }

    @Test
    fun `blank identity fields remove original authentication`() {
        val request = "GET / HTTP/1.1\r\nCookie: old=1\r\nAuthorization: old\r\n\r\n".toByteArray()
        val result = String(IdentityHeaders.apply(request, IdentityConfig("Anonymous", "")))
        assertFalse(result.contains("Cookie:", true))
        assertFalse(result.contains("Authorization:", true))
    }

    @Test
    fun `supports arbitrary headers and replaces names case insensitively`() {
        val request = ("GET / HTTP/1.1\r\nHost: example.com\r\nx-api-key: old\r\n" +
            "Cookie: stale\r\n\r\n").toByteArray()
        val identity = IdentityConfig("API", "X-API-Key: replacement\nX-Tenant: blue")
        val result = String(IdentityHeaders.apply(request, identity))

        assertEquals(1, Regex("(?im)^X-API-Key:").findAll(result).count())
        assertTrue(result.contains("X-API-Key: replacement"))
        assertTrue(result.contains("X-Tenant: blue"))
        assertFalse(result.contains("Cookie:", true))
    }

    @Test
    fun `rejects malformed and duplicate identity headers`() {
        assertTrue(IdentityHeaders.validationError(IdentityConfig("Bad", "not a header")) != null)
        assertTrue(IdentityHeaders.validationError(
            IdentityConfig("Duplicate", "X-Key: one\nx-key: two"),
        ) != null)
    }
}

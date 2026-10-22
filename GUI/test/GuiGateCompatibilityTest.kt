package burp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GuiGateCompatibilityTest {
    @Test
    fun `multiple upstream gates release independently`() {
        val engine = TestRequestEngine().apply { start() }
        engine.queue("GET /a HTTP/1.1\r\nHost: test.local\r\n\r\n", gateName = "first")
        engine.queue("GET /b HTTP/1.1\r\nHost: test.local\r\n\r\n", gateName = "second")
        val first = requireNotNull(engine.requestQueue.poll())
        val second = requireNotNull(engine.requestQueue.poll())
        val firstReleased = CountDownLatch(1)
        val secondReleased = CountDownLatch(1)
        val firstWaiter = thread(isDaemon = true) {
            first.gate!!.waitForGo()
            firstReleased.countDown()
        }
        val secondWaiter = thread(isDaemon = true) {
            second.gate!!.waitForGo()
            secondReleased.countDown()
        }

        engine.openGate("first")
        assertTrue(firstReleased.await(1, TimeUnit.SECONDS))
        assertEquals(1, secondReleased.count)

        engine.openGate("second")
        assertTrue(secondReleased.await(1, TimeUnit.SECONDS))
        firstWaiter.join(1_000)
        secondWaiter.join(1_000)
    }
}

package burp.gui

import burp.EngineConfig
import burp.ExecutionTechnique
import burp.GuiEngine
import http3.GateMode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EngineConfigTest {
    @Test
    fun `accepts supported synchronization pairs`() {
        assertNull(config(GuiEngine.THREADED, ExecutionTechnique.LAST_BYTE).validationError())
        assertNull(config(GuiEngine.BURP2, ExecutionTechnique.HTTP2_SINGLE_PACKET).validationError())
        assertNull(config(GuiEngine.HTTP3, ExecutionTechnique.HTTP3_GATE).validationError())
    }

    @Test
    fun `rejects unsupported synchronization pairs`() {
        assertNotNull(config(GuiEngine.BURP, ExecutionTechnique.LAST_BYTE).validationError())
        assertNotNull(config(GuiEngine.THREADED, ExecutionTechnique.HTTP2_SINGLE_PACKET).validationError())
        assertNotNull(config(GuiEngine.BURP2, ExecutionTechnique.HTTP3_GATE).validationError())
    }

    @Test
    fun `defaults match the script environment baseline`() {
        val defaults = EngineConfig.defaults()
        assertEquals(GuiEngine.THREADED, defaults.engine)
        assertEquals(ExecutionTechnique.CONCURRENT, defaults.technique)
        assertEquals(50, defaults.concurrentConnections)
        assertEquals(100, defaults.requestsPerConnection)
        assertEquals(100, defaults.maxQueueSize)
        assertEquals(10, defaults.timeoutSeconds)
        assertEquals(3, defaults.retries)
    }

    private fun config(engine: GuiEngine, technique: ExecutionTechnique) = EngineConfig(
        engine, technique, 20, 100, 100, 10, 3, false, GateMode.AUTO,
    )
}

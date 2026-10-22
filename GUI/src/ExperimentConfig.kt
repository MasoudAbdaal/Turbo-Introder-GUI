package burp

import http3.GateMode

data class RequestTemplateConfig(
    val id: Long,
    var name: String,
    var request: ByteArray,
    var count: Int = 1,
    val service: IHttpService? = null,
    val response: ByteArray? = null,
)

data class RequestSlotConfig(
    val id: Long,
    val templateId: Long,
    var request: ByteArray,
    var customizedRequest: Boolean = false,
    var customName: String? = null,
    var colorRgb: Int? = null,
    var identityName: String? = null,
    var releaseOffsetMs: Int = 0,
)

data class IdentityConfig(
    val name: String,
    val headers: String,
)

data class PlannedRequest(
    val row: GuiResult,
    val request: String,
    val label: String,
    val endpoint: String = "",
    val releaseOffsetMs: Int = 0,
    val slotId: Long? = null,
    val colorRgb: Int? = null,
)

enum class GuiRunMode(val displayName: String) {
    EXPERIMENT("Race / Experiment"),
    BRUTE_FORCE("Brute Force");

    override fun toString(): String = displayName
}

enum class ExecutionTechnique(val displayName: String) {
    SEQUENTIAL("Sequential"),
    CONCURRENT("Concurrent"),
    LAST_BYTE("HTTP/1 Last-Byte Sync"),
    HTTP2_SINGLE_PACKET("HTTP/2 Single-Packet"),
    HTTP3_GATE("HTTP/3 Gate");

    override fun toString(): String = displayName
}

data class EngineConfig(
    val engine: GuiEngine,
    val technique: ExecutionTechnique,
    val concurrentConnections: Int,
    val requestsPerConnection: Int,
    val maxQueueSize: Int,
    val timeoutSeconds: Int,
    val retries: Int,
    val pipeline: Boolean,
    val http3GateMode: GateMode,
    val startDelayMs: Int = 0,
    val requestDelayMs: Int = 0,
    val batchDelayMs: Int = 0,
) {
    fun validationError(): String? = when {
        technique == ExecutionTechnique.LAST_BYTE && engine != GuiEngine.THREADED ->
            "Last-byte synchronization requires THREADED"
        technique == ExecutionTechnique.HTTP2_SINGLE_PACKET && engine != GuiEngine.BURP2 ->
            "HTTP/2 single-packet requires BURP2"
        technique == ExecutionTechnique.HTTP3_GATE && engine != GuiEngine.HTTP3 ->
            "HTTP/3 gate requires HTTP3"
        engine == GuiEngine.HTTP3 && technique !in setOf(
            ExecutionTechnique.SEQUENTIAL,
            ExecutionTechnique.CONCURRENT,
            ExecutionTechnique.HTTP3_GATE,
        ) -> "The selected technique is not supported by HTTP3"
        pipeline && engine != GuiEngine.THREADED -> "Pipelining is supported only by THREADED"
        else -> null
    }

    companion object {
        fun defaults() = EngineConfig(
            engine = GuiEngine.THREADED,
            technique = ExecutionTechnique.CONCURRENT,
            concurrentConnections = 50,
            requestsPerConnection = 100,
            maxQueueSize = 100,
            timeoutSeconds = 10,
            retries = 3,
            pipeline = false,
            http3GateMode = GateMode.AUTO,
        )
    }
}

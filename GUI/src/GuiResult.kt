package burp

enum class GuiResultState(val displayName: String) {
    PLANNED("Planned"),
    QUEUED("Queued"),
    COMPLETED("Completed"),
    ERROR("Error"),
    CANCELLED("Cancelled"),
}

data class GuiResultSnapshot(
    val requestNumber: Int,
    val batchId: Int,
    val queueId: Int?,
    val label: String,
    val identity: String,
    val payload: String?,
    val status: Int?,
    val length: Int?,
    val words: Int?,
    val ttfb: Long?,
    val ttlb: Long?,
    val connectionId: String?,
    val responseOrder: Int?,
    val arrival: Long?,
    val anomalyRank: Int?,
    val state: GuiResultState,
    val error: String?,
    val request: ByteArray,
    val response: ByteArray?,
    val customValues: Map<String, String>,
    val service: IHttpService?,
    val releaseOffsetMs: Int,
    val gateOpenedMs: Long?,
    val slotId: Long?,
)

class GuiResult(
    private val requestNumber: Int,
    private val label: String,
    initialRequest: ByteArray,
    private val batchId: Int = 1,
    private val identity: String = "Original",
    private val service: IHttpService? = null,
    private val releaseOffsetMs: Int = 0,
    private val payload: String? = null,
    private val slotId: Long? = null,
) {
    private var queueId: Int? = null
    private var status: Int? = null
    private var length: Int? = null
    private var words: Int? = null
    private var ttfb: Long? = null
    private var ttlb: Long? = null
    private var connectionId: String? = null
    private var responseOrder: Int? = null
    private var arrival: Long? = null
    private var anomalyRank: Int? = null
    private var state = GuiResultState.PLANNED
    private var error: String? = null
    private var request = initialRequest.copyOf()
    private var response: ByteArray? = null
    private var customValues: Map<String, String> = emptyMap()
    private var engineRequest: Request? = null
    private var gateOpenedMs: Long? = null

    @Synchronized
    fun markQueued() {
        if (state == GuiResultState.PLANNED) state = GuiResultState.QUEUED
    }

    @Synchronized
    fun complete(engineRequest: Request) {
        this.engineRequest = engineRequest
        queueId = engineRequest.id
        status = engineRequest.code
        length = engineRequest.length
        words = engineRequest.wordcount
        ttfb = engineRequest.ttfb
        ttlb = engineRequest.ttlb
        connectionId = engineRequest.connectionId
        responseOrder = engineRequest.order.takeIf { engineRequest.gate != null }
        arrival = engineRequest.arrival
        anomalyRank = engineRequest.anomalyRank
        request = engineRequest.getRequestAsBytes()
        response = engineRequest.response?.let(Utils::stringToBytes)
        error = responseError(engineRequest.response)
        state = if (error == null) GuiResultState.COMPLETED else GuiResultState.ERROR
    }

    @Synchronized
    fun fail(message: String) {
        if (state == GuiResultState.COMPLETED || state == GuiResultState.ERROR) return
        error = message
        state = GuiResultState.ERROR
    }

    @Synchronized
    fun cancel() {
        if (state == GuiResultState.PLANNED || state == GuiResultState.QUEUED) {
            state = GuiResultState.CANCELLED
        }
    }

    @Synchronized
    fun isPending(): Boolean = state == GuiResultState.PLANNED || state == GuiResultState.QUEUED

    @Synchronized
    fun setCustomValues(values: Map<String, String>) {
        customValues = values.toMap()
    }

    @Synchronized
    fun refreshMetrics() {
        val source = engineRequest ?: return
        responseOrder = source.order.takeIf { source.gate != null }
        arrival = source.arrival
        anomalyRank = source.anomalyRank
    }

    @Synchronized
    fun releaseEngineRequest() {
        engineRequest = null
    }

    @Synchronized
    fun markGateOpened(elapsedMs: Long) {
        gateOpenedMs = elapsedMs
    }

    @Synchronized
    fun snapshot() = GuiResultSnapshot(
        requestNumber,
        batchId,
        queueId,
        label,
        identity,
        payload,
        status,
        length,
        words,
        ttfb,
        ttlb,
        connectionId,
        responseOrder,
        arrival,
        anomalyRank,
        state,
        error,
        request.copyOf(),
        response?.copyOf(),
        customValues.toMap(),
        service,
        releaseOffsetMs,
        gateOpenedMs,
        slotId,
    )

    @Synchronized
    fun tableSnapshot() = GuiResultSnapshot(
        requestNumber, batchId, queueId, label, identity, payload, status, length, words,
        ttfb, ttlb, connectionId, responseOrder, arrival, anomalyRank, state, error,
        ByteArray(0), null, customValues, service, releaseOffsetMs, gateOpenedMs, slotId,
    )

    private fun responseError(value: String?): String? = when {
        value == null -> "No response received"
        value == "null" -> "No response received"
        value == "early-response" -> "Connection returned an early response"
        value.startsWith("The server closed the connection") -> value
        else -> null
    }
}

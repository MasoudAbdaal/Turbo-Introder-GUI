package burp

object RequestExecutionPlanner {
    fun createBatches(
        model: RequestScenarioModel,
        identities: List<IdentityConfig>,
        batchCount: Int,
        engine: GuiEngine,
        releaseOffsetsEnabled: Boolean,
    ): List<List<PlannedRequest>> {
        val identitiesByName = identities.associateBy { it.name }
        val orderedSlots = model.slots.toList()
        var requestNumber = 0
        return (1..batchCount).map { batch ->
            orderedSlots.map { slot ->
                val template = requireNotNull(model.template(slot.templateId))
                val identity = slot.identityName?.let(identitiesByName::get)
                val offset = if (releaseOffsetsEnabled) slot.releaseOffsetMs else 0
                val applied = IdentityHeaders.apply(slot.request.copyOf(), identity)
                val prepared = engine.prepareRequest(String(applied, Charsets.ISO_8859_1))
                val label = model.slotLabel(slot)
                val row = GuiResult(
                    requestNumber = ++requestNumber,
                    label = label,
                    initialRequest = prepared.toByteArray(Charsets.ISO_8859_1),
                    batchId = batch,
                    identity = identity?.name ?: "Original",
                    service = template.service,
                    releaseOffsetMs = offset,
                    slotId = slot.id,
                )
                PlannedRequest(
                    row = row,
                    request = prepared,
                    label = label,
                    endpoint = endpoint(template.service),
                    releaseOffsetMs = offset,
                    slotId = slot.id,
                    colorRgb = slot.colorRgb,
                )
            }
        }
    }

    private fun endpoint(service: IHttpService?): String {
        service ?: return ""
        return "${service.protocol}://${service.host}:${service.port}"
    }
}

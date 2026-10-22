package burp

class RequestScenarioModel {
    private var nextTemplateId = 1L
    private var nextSlotId = 1L
    val templates = mutableListOf<RequestTemplateConfig>()
    val slots = mutableListOf<RequestSlotConfig>()

    fun appendTemplate(
        name: String,
        request: ByteArray,
        service: IHttpService?,
        response: ByteArray?,
    ): RequestTemplateConfig {
        val template = RequestTemplateConfig(
            nextTemplateId++, name, request.copyOf(), 1, service, response?.copyOf(),
        )
        templates += template
        slots += newSlot(template)
        return template
    }

    fun duplicateTemplate(templateId: Long, name: String): RequestTemplateConfig {
        val source = requireNotNull(template(templateId))
        return appendTemplate(name, source.request, source.service, source.response)
    }

    fun updateTemplate(templateId: Long, name: String, request: ByteArray, count: Int) {
        val template = requireNotNull(template(templateId))
        template.name = name
        if (!template.request.contentEquals(request)) {
            template.request = request.copyOf()
            slots.filter { it.templateId == templateId && !it.customizedRequest }
                .forEach { it.request = request.copyOf() }
        }
        resizeTemplate(template, count)
    }

    fun removeTemplate(templateId: Long) {
        templates.removeIf { it.id == templateId }
        slots.removeIf { it.templateId == templateId }
    }

    fun clearTemplates() {
        templates.clear()
        slots.clear()
        nextTemplateId = 1L
        nextSlotId = 1L
    }

    fun updateSlotRequest(slotId: Long, request: ByteArray) {
        val slot = requireNotNull(slot(slotId))
        val template = requireNotNull(template(slot.templateId))
        slot.request = request.copyOf()
        slot.customizedRequest = !slot.request.contentEquals(template.request)
    }

    fun resetSlotRequest(slotId: Long) {
        val slot = requireNotNull(slot(slotId))
        slot.request = requireNotNull(template(slot.templateId)).request.copyOf()
        slot.customizedRequest = false
    }

    fun renameSlot(slotId: Long, name: String?) {
        slot(slotId)?.customName = name?.trim()?.takeIf(String::isNotEmpty)
    }

    fun colorSlot(slotId: Long, rgb: Int?) {
        slot(slotId)?.colorRgb = rgb
    }

    fun assignIdentity(slotIds: Set<Long>, identityName: String?) {
        slots.filter { it.id in slotIds }.forEach { it.identityName = identityName }
    }

    fun assignReleaseOffset(slotIds: Set<Long>, offsetMs: Int) {
        require(offsetMs >= 0)
        slots.filter { it.id in slotIds }.forEach { it.releaseOffsetMs = offsetMs }
    }

    fun moveSlot(slotId: Long, direction: Int): Boolean {
        val index = slots.indexOfFirst { it.id == slotId }
        val target = index + direction
        if (index !in slots.indices || target !in slots.indices) return false
        val moved = slots.removeAt(index)
        slots.add(target, moved)
        return true
    }

    fun moveSlotToInsertion(slotId: Long, insertionIndex: Int): Boolean {
        val source = slots.indexOfFirst { it.id == slotId }
        if (source !in slots.indices || insertionIndex !in 0..slots.size) return false
        val destination = (if (insertionIndex > source) insertionIndex - 1 else insertionIndex)
            .coerceIn(0, slots.lastIndex)
        if (destination == source) return false
        val moved = slots.removeAt(source)
        slots.add(destination, moved)
        return true
    }

    fun template(id: Long): RequestTemplateConfig? = templates.firstOrNull { it.id == id }
    fun slot(id: Long): RequestSlotConfig? = slots.firstOrNull { it.id == id }
    fun slotLabel(slot: RequestSlotConfig): String =
        slot.customName ?: template(slot.templateId)?.name ?: "Request"

    private fun resizeTemplate(template: RequestTemplateConfig, requestedCount: Int) {
        val count = requestedCount.coerceAtLeast(1)
        val current = slots.filter { it.templateId == template.id }
        when {
            count > current.size -> {
                val insertion = slots.indexOfLast { it.templateId == template.id }.let {
                    if (it < 0) slots.size else it + 1
                }
                repeat(count - current.size) { offset -> slots.add(insertion + offset, newSlot(template)) }
            }
            count < current.size -> current.takeLast(current.size - count).forEach { removed ->
                slots.removeIf { it.id == removed.id }
            }
        }
        template.count = count
    }

    private fun newSlot(template: RequestTemplateConfig) = RequestSlotConfig(
        id = nextSlotId++,
        templateId = template.id,
        request = template.request.copyOf(),
    )
}

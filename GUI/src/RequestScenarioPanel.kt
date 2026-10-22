package burp

import java.awt.BorderLayout
import java.awt.Font
import java.awt.Container
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

class RequestScenarioPanel : JPanel(BorderLayout()) {
    private val model = RequestScenarioModel()
    private val templateNames = javax.swing.DefaultListModel<String>()
    private val templateList = JList(templateNames)
    private val labelField = javax.swing.JTextField(14)
    private val countSpinner = JSpinner(SpinnerNumberModel(1, 1, 10_000, 1))
    private val editor = Utils.callbacks.createMessageEditor(Controller(), true)
    private val identityModel = DefaultTableModel(arrayOf("Name"), 0)
    private val identityTable = JTable(identityModel)
    private val identityHeaders = JTextArea(5, 32)
    private val identityHeaderValues = mutableListOf<String>()
    private val knownIdentityNames = mutableListOf<String>()
    private var openSelectedRequest: () -> Unit = {}
    private val mapping = RequestMappingPanel(model, ::selectSlot) { slotId ->
        selectSlot(slotId)
        openSelectedRequest()
    }
    private val requestSettings = JTabbedPane()
    private val bruteForce = BruteForceControls(::addPayloadPosition, ::clearPayloadPositions)
    private var selectedTemplateId: Long? = null
    private var selectedSlotId: Long? = null
    private var selectedIdentity = -1
    private var loading = false
    private var loadingIdentity = false
    private var releaseOffsetsAvailable = false
    private var nextTemplateNumber = 1

    init {
        templateList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        editor.setMessage(ByteArray(0), true)
        val addTemplate = JButton("Add")
        val removeTemplate = JButton("Remove")
        val cleanRequests = JButton("Clean Up Requests")
        val templateControls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Name:")); add(labelField)
            add(JLabel("Count:")); add(countSpinner)
            add(addTemplate); add(removeTemplate); add(cleanRequests)
        }
        val editorPanel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                add(templateControls, BorderLayout.NORTH)
                add(bruteForce, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(editor.component, BorderLayout.CENTER)
        }
        add(JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JPanel(BorderLayout()).apply {
                add(JScrollPane(templateList), BorderLayout.CENTER)
                preferredSize = java.awt.Dimension(140, 160)
            },
            editorPanel,
        ).apply { dividerLocation = 140 }, BorderLayout.CENTER)

        val addIdentity = JButton("Add identity")
        val removeIdentity = JButton("Remove identity")
        val identities = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(addIdentity); add(removeIdentity)
            }, BorderLayout.NORTH)
            add(JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                JScrollPane(identityTable),
                JPanel(BorderLayout()).apply {
                    add(JLabel("Headers (one per line)"), BorderLayout.NORTH)
                    add(JScrollPane(identityHeaders), BorderLayout.CENTER)
                },
            ).apply { resizeWeight = 0.35 }, BorderLayout.CENTER)
        }
        requestSettings.apply {
            font = font.deriveFont(Font.BOLD)
            addTab("Identities", identities)
            addTab("Request Mapping", mapping)
        }

        templateList.addListSelectionListener {
            if (it.valueIsAdjusting || loading) return@addListSelectionListener
            saveEditorContent()
            val template = model.templates.getOrNull(templateList.selectedIndex)
            selectedTemplateId = template?.id
            selectedSlotId = null
            loadTemplate(template)
        }
        labelField.document.addDocumentListener(SimpleDocumentListener {
            if (loading) return@SimpleDocumentListener
            val template = selectedTemplate() ?: return@SimpleDocumentListener
            template.name = labelField.text
            refreshTemplateNames()
            mapping.refresh(identityNames())
        })
        countSpinner.addChangeListener {
            if (!loading) {
                saveEditorContent()
                selectedTemplate()?.let { template ->
                    model.updateTemplate(template.id, normalizedName(template), template.request, countSpinner.value as Int)
                }
                mapping.refresh(identityNames())
            }
        }
        addTemplate.addActionListener {
            saveEditorContent()
            val selected = selectedTemplate() ?: return@addActionListener
            val added = model.duplicateTemplate(selected.id, nextTemplateName())
            selectTemplate(added.id)
            refreshAll()
        }
        removeTemplate.addActionListener {
            val template = selectedTemplate() ?: return@addActionListener
            if (model.templates.size == 1) return@addActionListener
            saveEditorContent()
            val oldIndex = model.templates.indexOf(template)
            model.removeTemplate(template.id)
            val next = model.templates.getOrNull(minOf(oldIndex, model.templates.lastIndex))
            selectTemplate(next?.id)
            refreshAll()
        }
        cleanRequests.addActionListener {
            if (model.templates.isEmpty()) return@addActionListener
            val choice = JOptionPane.showConfirmDialog(
                this,
                "Remove all configured request templates? Identities, settings, and results are preserved.",
                "Clean Up Requests",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )
            if (choice == JOptionPane.YES_OPTION) clearRequests()
        }
        configureIdentities(addIdentity, removeIdentity)
        refreshAll()
    }

    fun appendRequest(source: IHttpRequestResponse) {
        saveEditorContent()
        val added = model.appendTemplate(
            nextTemplateName(), source.request, source.httpService, source.response,
        )
        selectTemplate(added.id)
        refreshAll()
    }

    fun clearRequests() {
        model.clearTemplates()
        selectedTemplateId = null
        selectedSlotId = null
        nextTemplateNumber = 1
        loading = true
        labelField.text = ""
        countSpinner.value = 1
        editor.setMessage(ByteArray(0), true)
        loading = false
        refreshAll()
    }

    fun hasRequests(): Boolean = model.slots.isNotEmpty()

    fun requestSettingsComponent(): java.awt.Component = requestSettings

    fun addRequestSettingsTab(component: java.awt.Component) {
        if (requestSettings.indexOfTab(REQUEST_TAB) < 0) requestSettings.addTab(REQUEST_TAB, component)
    }

    fun onEditSelectedRequest(action: () -> Unit) {
        openSelectedRequest = action
    }

    fun validationError(allowPayloadMarkers: Boolean = false): String? {
        val identityError = identities().firstNotNullOfOrNull { identity ->
            IdentityHeaders.validationError(identity)?.let { "${identity.name}: $it" }
        }
        if (identityError != null) return identityError
        return if (!allowPayloadMarkers && model.slots.any { PayloadPositions.hasPosition(it.request) }) {
            "Payload markers cannot be sent in Race / Experiment mode. Clear them or select Brute Force."
        } else null
    }

    fun setReleaseOffsetsEnabled(enabled: Boolean) {
        releaseOffsetsAvailable = enabled
        mapping.setReleaseOffsetsEnabled(enabled)
    }

    fun setBruteForceMode(enabled: Boolean) {
        bruteForce.isVisible = enabled
        revalidate()
    }

    fun bruteForceValidationError(): String? {
        saveEditorContent()
        return bruteForce.validationError()
            ?: if (bruteForceTarget()?.request?.let(PayloadPositions::hasPosition) != true) {
                "Add a payload position to the selected request."
            } else null
    }

    fun bruteForceWordlist() = bruteForce.wordlist()

    fun bruteForceTarget(): BruteForceTarget? {
        saveEditorContent()
        val slot = selectedSlotId?.let(model::slot)
            ?: selectedTemplateId?.let { id -> model.slots.firstOrNull { it.templateId == id } }
            ?: return null
        val template = model.template(slot.templateId) ?: return null
        val identity = slot.identityName?.let { name -> identities().firstOrNull { it.name == name } }
        return BruteForceTarget(
            slot.request.copyOf(), model.slotLabel(slot), endpoint(template.service), identity, template.service,
        )
    }

    fun createBatches(batchCount: Int, engine: GuiEngine): List<List<PlannedRequest>> {
        saveEditorContent()
        return RequestExecutionPlanner.createBatches(
            model, identities(), batchCount, engine, releaseOffsetsAvailable,
        )
    }

    fun requiredLastByteConnections(): Int {
        saveEditorContent()
        return model.slots.groupBy { endpoint(model.template(it.templateId)?.service) }
            .maxOfOrNull { it.value.size } ?: 0
    }

    fun setControlsEnabled(enabled: Boolean) {
        setEnabledRecursively(this, enabled)
        setEnabledRecursively(requestSettings, enabled)
        mapping.setControlsEnabled(enabled)
    }

    private fun selectSlot(slotId: Long) {
        saveEditorContent()
        val slot = model.slot(slotId) ?: return
        selectedSlotId = slotId
        selectedTemplateId = slot.templateId
        loading = true
        templateList.selectedIndex = model.templates.indexOfFirst { it.id == slot.templateId }
        val template = requireNotNull(model.template(slot.templateId))
        labelField.text = template.name
        countSpinner.value = template.count
        editor.setMessage(slot.request, true)
        loading = false
    }

    private fun saveEditorContent() {
        val message = editor.message?.copyOf() ?: return
        selectedSlotId?.let {
            model.updateSlotRequest(it, message)
            return
        }
        val template = selectedTemplate() ?: return
        model.updateTemplate(
            template.id,
            normalizedName(template),
            message,
            countSpinner.value as Int,
        )
    }

    private fun addPayloadPosition() {
        val request = editor.message?.copyOf() ?: return
        runCatching { PayloadPositions.add(request, editor.selectionBounds) }
            .onSuccess { updated -> editor.setMessage(updated, true); saveEditorContent() }
            .onFailure { JOptionPane.showMessageDialog(this, it.message, "Payload position", JOptionPane.ERROR_MESSAGE) }
    }

    private fun clearPayloadPositions() {
        val request = editor.message?.copyOf() ?: return
        editor.setMessage(PayloadPositions.clear(request), true)
        saveEditorContent()
    }

    private fun loadTemplate(template: RequestTemplateConfig?) {
        loading = true
        labelField.text = template?.name.orEmpty()
        countSpinner.value = template?.count ?: 1
        editor.setMessage(template?.request ?: ByteArray(0), true)
        loading = false
    }

    private fun selectTemplate(templateId: Long?) {
        selectedSlotId = null
        selectedTemplateId = templateId
        loading = true
        templateList.selectedIndex = model.templates.indexOfFirst { it.id == templateId }
        loading = false
        loadTemplate(templateId?.let(model::template))
    }

    private fun selectedTemplate(): RequestTemplateConfig? = selectedTemplateId?.let(model::template)

    private fun normalizedName(template: RequestTemplateConfig): String =
        labelField.text.trim().ifEmpty { template.name.ifEmpty { "Request" } }

    private fun refreshAll() {
        refreshTemplateNames()
        mapping.refresh(identityNames())
    }

    private fun refreshTemplateNames() {
        val selected = selectedTemplateId
        loading = true
        templateNames.clear()
        model.templates.forEach { templateNames.addElement(it.name) }
        templateList.selectedIndex = model.templates.indexOfFirst { it.id == selected }
        loading = false
    }

    private fun nextTemplateName(): String {
        val number = nextTemplateNumber++
        return if (number <= 26) "Request ${('A'.code + number - 1).toChar()}" else "Request $number"
    }

    private fun configureIdentities(addButton: JButton, removeButton: JButton) {
        addButton.addActionListener {
            saveIdentityHeaders()
            val name = nextIdentityName()
            identityHeaderValues += ""
            knownIdentityNames += name
            identityModel.addRow(arrayOf(name))
            identityTable.setRowSelectionInterval(identityModel.rowCount - 1, identityModel.rowCount - 1)
            mapping.refresh(identityNames())
        }
        removeButton.addActionListener {
            val row = identityTable.selectedRow
            if (row >= 0) removeIdentity(identityTable.convertRowIndexToModel(row))
        }
        identityTable.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            saveIdentityHeaders()
            selectedIdentity = identityTable.selectedRow.takeIf { row -> row >= 0 }
                ?.let(identityTable::convertRowIndexToModel) ?: -1
            loadIdentityHeaders(selectedIdentity)
        }
        identityHeaders.document.addDocumentListener(SimpleDocumentListener {
            if (!loadingIdentity && selectedIdentity in identityHeaderValues.indices) {
                identityHeaderValues[selectedIdentity] = identityHeaders.text
            }
        })
        identityModel.addTableModelListener {
            (0 until minOf(identityModel.rowCount, knownIdentityNames.size)).forEach { row ->
                val current = identityModel.getValueAt(row, 0)?.toString().orEmpty()
                val previous = knownIdentityNames[row]
                if (current != previous) {
                    model.slots.filter { slot -> slot.identityName == previous }
                        .forEach { slot -> slot.identityName = current }
                    knownIdentityNames[row] = current
                }
            }
            mapping.refresh(identityNames())
        }
    }

    private fun removeIdentity(row: Int) {
        saveIdentityHeaders()
        val removed = identityModel.getValueAt(row, 0)?.toString().orEmpty()
        selectedIdentity = -1
        identityHeaderValues.removeAt(row)
        knownIdentityNames.removeAt(row)
        identityModel.removeRow(row)
        model.slots.filter { it.identityName == removed }.forEach { it.identityName = null }
        if (identityModel.rowCount > 0) {
            val next = minOf(row, identityModel.rowCount - 1)
            identityTable.setRowSelectionInterval(next, next)
        } else loadIdentityHeaders(-1)
        mapping.refresh(identityNames())
    }

    private fun identities(): List<IdentityConfig> {
        saveIdentityHeaders()
        return (0 until identityModel.rowCount).mapNotNull { row ->
            val name = identityModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            if (name.isEmpty()) null else IdentityConfig(name, identityHeaderValues.getOrElse(row) { "" })
        }
    }

    private fun identityNames(): List<String> = (0 until identityModel.rowCount).mapNotNull { row ->
        identityModel.getValueAt(row, 0)?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun nextIdentityName(): String {
        var number = identityModel.rowCount + 1
        val names = identityNames().toSet()
        while ("Identity $number" in names) number++
        return "Identity $number"
    }

    private fun saveIdentityHeaders() {
        if (!loadingIdentity && selectedIdentity in identityHeaderValues.indices) {
            identityHeaderValues[selectedIdentity] = identityHeaders.text
        }
    }

    private fun loadIdentityHeaders(index: Int) {
        loadingIdentity = true
        identityHeaders.text = identityHeaderValues.getOrElse(index) { "" }
        identityHeaders.isEnabled = index in identityHeaderValues.indices
        loadingIdentity = false
    }

    private fun endpoint(service: IHttpService?): String {
        requireNotNull(service) { "Imported request has no HTTP service" }
        val host = if (service.host.contains(':')) "[${service.host}]" else service.host
        return "${service.protocol}://$host:${service.port}"
    }

    private fun setEnabledRecursively(container: Container, enabled: Boolean) {
        container.components.forEach {
            it.isEnabled = enabled
            if (it is Container) setEnabledRecursively(it, enabled)
        }
    }

    private inner class Controller : IMessageEditorController {
        private fun template() = selectedTemplate()
        override fun getHttpService(): IHttpService? = template()?.service
        override fun getRequest(): ByteArray? = selectedSlotId?.let(model::slot)?.request ?: template()?.request
        override fun getResponse(): ByteArray? = template()?.response
    }

    companion object {
        const val REQUEST_TAB = "Request"
    }
}

fun interface SimpleDocumentListener : javax.swing.event.DocumentListener {
    fun changed()
    override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = changed()
    override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = changed()
    override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = changed()
}

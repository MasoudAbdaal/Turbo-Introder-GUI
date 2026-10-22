package burp

import http3.GateMode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel

class EngineConfigPanel : JPanel(BorderLayout()) {
    private val mode = JComboBox(GuiRunMode.entries.toTypedArray())
    private val engine = JComboBox(GuiEngine.entries.toTypedArray())
    private val technique = JComboBox<ExecutionTechnique>()
    private val batches = spinner(1, 1, 1_000)
    private val connections = spinner(50, 1, 10_000)
    private val requestsPerConnection = spinner(100, 1, 1_000_000)
    private val maxQueue = spinner(100, 1, 1_000_000)
    private val timeout = spinner(10, 1, 3_600)
    private val retries = spinner(3, 0, 100)
    private val pipeline = JCheckBox()
    private val gateMode = JComboBox(GateMode.entries.toTypedArray())
    private val startDelay = spinner(0, 0, 3_600_000)
    private val requestDelay = spinner(0, 0, 60_000)
    private val batchDelay = spinner(0, 0, 3_600_000)
    private val workspaceFields = mutableListOf<JPanel>()
    private val advancedFields = mutableListOf<JPanel>()
    private val workspaceGrid = JPanel(GridBagLayout())
    private val advancedGrid = JPanel(GridBagLayout())
    private val advancedRequestPanel = JPanel(BorderLayout())
    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        workspaceField("mode", mode, "GUI-level execution mode: mapped experiment or wordlist-based Brute Force expansion.")
        workspaceField("engine", engine, "HTTP transport used to send requests. Protocol and synchronization support vary by engine.")
        workspaceField("technique", technique, "GUI-level sequential, concurrent, or protocol-specific synchronization technique.")
        workspaceField("concurrentConnections", connections, "Maximum concurrent connections. More connections increase parallelism but do not guarantee arrival order.")
        workspaceField("requestsPerConnection", requestsPerConnection, "Maximum requests carried by each connection, subject to engine and protocol behavior.")
        workspaceField("pipeline", pipeline, "Allows THREADED HTTP/1 requests to be written without waiting for each response; unsupported by other engines.")
        advancedField("batches", batches, "GUI-level number of repeated experiment runs. Batches are not synchronization gates.")
        advancedField("maxQueueSize", maxQueue, "Maximum requests waiting in the engine queue before submission blocks.")
        advancedField("timeout", timeout, "THREADED socket read timeout in seconds; unavailable for Burp-managed transports.")
        advancedField("maxRetriesPerRequest", retries, "Maximum transport retries for a failed request. Zero disables retries.")
        advancedField("gateMode", gateMode, "HTTP/3 gate release mechanism. Used only with the HTTP3 Gate technique.")
        advancedField("startDelayMs", startDelay, "GUI-level delay before the first run starts, in milliseconds.")
        advancedField("requestDelayMs", requestDelay, "GUI-level delay between ungated queue submissions; it is not a synchronization control.")
        advancedField("batchDelayMs", batchDelay, "GUI-level delay between completed experiment batches, in milliseconds.")
        val workspaceScroll = JScrollPane(
            workspaceGrid,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).apply {
            border = null
            preferredSize = java.awt.Dimension(800, 72)
            viewport.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent?) =
                    reflow(workspaceGrid, workspaceFields, viewport.width)
            })
        }
        val advancedScroll = JScrollPane(
            advancedGrid,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).apply {
            border = null
            viewport.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent?) =
                    reflow(advancedGrid, advancedFields, viewport.width)
            })
        }
        advancedRequestPanel.add(advancedScroll, BorderLayout.CENTER)
        add(workspaceScroll, BorderLayout.CENTER)
        mode.addActionListener { updateCompatibility(); notifyConfigChanged() }
        engine.addActionListener { updateCompatibility(); notifyConfigChanged() }
        technique.addActionListener { updateDependentControls(); notifyConfigChanged() }
        restoreDefaults()
        reflow(workspaceGrid, workspaceFields, 800)
        reflow(advancedGrid, advancedFields, 600)
    }

    fun batchCount(): Int = batches.value as Int
    fun runMode(): GuiRunMode = mode.selectedItem as GuiRunMode
    fun isLastByteSync(): Boolean = engine.selectedItem == GuiEngine.THREADED &&
        technique.selectedItem == ExecutionTechnique.LAST_BYTE
    fun addConfigChangeListener(listener: () -> Unit) { changeListeners += listener }
    fun advancedRequestComponent(): Component = advancedRequestPanel

    fun config(): EngineConfig = EngineConfig(
        engine.selectedItem as GuiEngine,
        technique.selectedItem as ExecutionTechnique,
        connections.value as Int,
        requestsPerConnection.value as Int,
        maxQueue.value as Int,
        timeout.value as Int,
        retries.value as Int,
        pipeline.isSelected,
        gateMode.selectedItem as GateMode,
        startDelay.value as Int,
        requestDelay.value as Int,
        batchDelay.value as Int,
    )

    fun restoreDefaults() {
        val defaults = EngineConfig.defaults()
        mode.selectedItem = GuiRunMode.EXPERIMENT
        engine.selectedItem = defaults.engine
        updateCompatibility()
        technique.selectedItem = defaults.technique
        batches.value = 1
        connections.value = defaults.concurrentConnections
        requestsPerConnection.value = defaults.requestsPerConnection
        maxQueue.value = defaults.maxQueueSize
        timeout.value = defaults.timeoutSeconds
        retries.value = defaults.retries
        pipeline.isSelected = defaults.pipeline
        gateMode.selectedItem = defaults.http3GateMode
        startDelay.value = defaults.startDelayMs
        requestDelay.value = defaults.requestDelayMs
        batchDelay.value = defaults.batchDelayMs
        updateCompatibility()
        notifyConfigChanged()
    }

    fun setControlsEnabled(enabled: Boolean) {
        setEnabledRecursively(this, enabled)
        setEnabledRecursively(advancedRequestPanel, enabled)
        if (enabled) updateCompatibility()
    }

    private fun updateCompatibility() {
        val selectedEngine = engine.selectedItem as? GuiEngine ?: return
        val previous = technique.selectedItem as? ExecutionTechnique
        val engineTechniques = when (selectedEngine) {
            GuiEngine.THREADED -> arrayOf(
                ExecutionTechnique.SEQUENTIAL,
                ExecutionTechnique.CONCURRENT,
                ExecutionTechnique.LAST_BYTE,
            )
            GuiEngine.BURP -> arrayOf(ExecutionTechnique.SEQUENTIAL, ExecutionTechnique.CONCURRENT)
            GuiEngine.BURP2 -> arrayOf(
                ExecutionTechnique.SEQUENTIAL,
                ExecutionTechnique.CONCURRENT,
                ExecutionTechnique.HTTP2_SINGLE_PACKET,
            )
            GuiEngine.HTTP3 -> arrayOf(
                ExecutionTechnique.SEQUENTIAL,
                ExecutionTechnique.CONCURRENT,
                ExecutionTechnique.HTTP3_GATE,
            )
        }
        val supported = if (runMode() == GuiRunMode.BRUTE_FORCE) {
            engineTechniques.filter { it in setOf(ExecutionTechnique.SEQUENTIAL, ExecutionTechnique.CONCURRENT) }
                .toTypedArray()
        } else engineTechniques
        technique.model = javax.swing.DefaultComboBoxModel(supported)
        if (previous in supported) technique.selectedItem = previous
        val threaded = selectedEngine == GuiEngine.THREADED
        requestsPerConnection.isEnabled = threaded || selectedEngine == GuiEngine.HTTP3
        timeout.isEnabled = threaded
        pipeline.isEnabled = threaded
        if (!threaded) pipeline.isSelected = false
        batches.isEnabled = runMode() != GuiRunMode.BRUTE_FORCE
        batchDelay.isEnabled = runMode() != GuiRunMode.BRUTE_FORCE
        updateDependentControls()
    }

    private fun updateDependentControls() {
        val selectedEngine = engine.selectedItem as? GuiEngine ?: return
        gateMode.isEnabled = selectedEngine == GuiEngine.HTTP3 &&
            technique.selectedItem == ExecutionTechnique.HTTP3_GATE
        requestDelay.isEnabled = technique.selectedItem in setOf(
            ExecutionTechnique.SEQUENTIAL,
            ExecutionTechnique.CONCURRENT,
        )
    }

    private fun reflow(grid: JPanel, fields: List<JPanel>, width: Int) {
        val columns = (width.coerceAtLeast(220) / 230).coerceAtLeast(1)
        grid.removeAll()
        fields.forEachIndexed { index, panel ->
            grid.add(panel, GridBagConstraints().apply {
                gridx = index % columns
                gridy = index / columns
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 4, 2, 4)
            })
        }
        grid.revalidate()
        grid.repaint()
    }

    private fun workspaceField(label: String, component: Component, tooltip: String) =
        field(workspaceFields, label, component, tooltip)

    private fun advancedField(label: String, component: Component, tooltip: String) =
        field(advancedFields, label, component, tooltip)

    private fun field(fields: MutableList<JPanel>, label: String, component: Component, tooltip: String) {
        fields += JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
            toolTipText = tooltip
            add(JLabel("$label:").apply { toolTipText = tooltip })
            (component as? JComponent)?.toolTipText = tooltip
            add(component)
        }
    }

    private fun setEnabledRecursively(container: Container, enabled: Boolean) {
        container.components.forEach {
            it.isEnabled = enabled
            if (it is Container) setEnabledRecursively(it, enabled)
        }
    }

    private fun notifyConfigChanged() = changeListeners.forEach { it() }
    private fun spinner(value: Int, min: Int, max: Int) = JSpinner(SpinnerNumberModel(value, min, max, 1))
}

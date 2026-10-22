package burp

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.JTabbedPane
import javax.swing.Timer

class TurboGuiWorkspace : JPanel(BorderLayout()) {
    private val executor = Executors.newFixedThreadPool(2)
    private val scenarioPanel = RequestScenarioPanel()
    private val enginePanel = EngineConfigPanel()
    private val analysisPanel = ResultsAnalysisPanel()
    private val startButton = JButton("Start")
    private val cancelButton = JButton("Cancel")
    private val restoreButton = JButton("Restore to Default")
    private val statusLabel = JLabel("Import a request to begin")
    private val tabs = JTabbedPane()
    @Volatile private var activeRunner: ExperimentRunner? = null
    @Volatile private var activePreparation: AtomicBoolean? = null
    private val pendingBruteUpdates = ConcurrentLinkedQueue<GuiResult>()
    private val completedBruteRows = Collections.newSetFromMap(ConcurrentHashMap<GuiResult, Boolean>())
    private val completedBruteCount = AtomicInteger()
    @Volatile private var currentBruteChunk = 0
    @Volatile private var processedBruteCount = 0
    @Volatile private var bruteForceRunning = false
    private val bruteUpdateTimer = Timer(100) { flushBruteUpdates() }

    init {
        cancelButton.isEnabled = false
        val execution = JPanel(BorderLayout()).apply {
            add(enginePanel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(restoreButton)
                add(startButton)
                add(cancelButton)
                add(statusLabel)
            }, BorderLayout.SOUTH)
        }
        val workspace = JPanel(BorderLayout()).apply {
            add(execution, BorderLayout.NORTH)
            add(scenarioPanel, BorderLayout.CENTER)
        }
        scenarioPanel.addRequestSettingsTab(enginePanel.advancedRequestComponent())
        tabs.font = tabs.font.deriveFont(Font.BOLD)
        tabs.addTab(WORKSPACE_TAB, workspace)
        tabs.addTab(ADVANCED_SETTINGS_TAB, scenarioPanel.requestSettingsComponent())
        tabs.addTab(RESPONSES_TAB, analysisPanel.responsesComponent())
        tabs.selectedIndex = 0
        add(tabs, BorderLayout.CENTER)
        preferredSize = Dimension(1000, 750)
        startButton.addActionListener { startRun() }
        cancelButton.addActionListener { cancelRun() }
        restoreButton.addActionListener { restoreDefaults() }
        scenarioPanel.onEditSelectedRequest { tabs.selectedIndex = 0 }
        analysisPanel.onOpenResponse { tabs.selectedIndex = 2 }
        enginePanel.addConfigChangeListener {
            synchronizeMode()
        }
        synchronizeMode()
        Utils.callbacks.customizeUiComponent(this)
    }

    fun appendRequest(input: IHttpRequestResponse) {
        check(SwingUtilities.isEventDispatchThread()) { "Requests must be imported on the Swing event thread" }
        scenarioPanel.appendRequest(input)
        statusLabel.text = "Request added"
    }

    fun shutdown() {
        activePreparation?.set(true)
        activeRunner?.cancel()
        bruteUpdateTimer.stop()
        executor.shutdownNow()
    }

    private fun startRun() {
        if (activeRunner != null || activePreparation != null) return
        if (!scenarioPanel.hasRequests()) {
            JOptionPane.showMessageDialog(
                this,
                "Add at least one request from another Burp tool before starting.",
                "No requests configured",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        val config = enginePanel.config()
        scenarioPanel.validationError(enginePanel.runMode() == GuiRunMode.BRUTE_FORCE)?.let {
            JOptionPane.showMessageDialog(this, it, "Invalid identity headers", JOptionPane.ERROR_MESSAGE)
            return
        }
        config.validationError()?.let {
            JOptionPane.showMessageDialog(this, it, "Unsupported configuration", JOptionPane.ERROR_MESSAGE)
            return
        }
        val requiredConnections = scenarioPanel.requiredLastByteConnections()
        if (config.technique == ExecutionTechnique.LAST_BYTE &&
            config.concurrentConnections < requiredConnections
        ) {
            JOptionPane.showMessageDialog(
                this,
                "Last-byte synchronization needs at least one connection per request " +
                    "on each target ($requiredConnections connections for the largest target group).",
                "Insufficient connections",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        if (config.engine == GuiEngine.HTTP3 && !Utils.isBurpProfessional()) {
            JOptionPane.showMessageDialog(
                this,
                "HTTP3 requires Burp Suite Professional.",
                "Unsupported edition",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        if (enginePanel.runMode() == GuiRunMode.BRUTE_FORCE) {
            startBruteForce(config)
            return
        }

        val batches = scenarioPanel.createBatches(enginePanel.batchCount(), config.engine)
        launchRunner(config, batches, false)
    }

    private fun startBruteForce(config: EngineConfig) {
        scenarioPanel.bruteForceValidationError()?.let {
            JOptionPane.showMessageDialog(this, it, "Invalid Brute Force configuration", JOptionPane.ERROR_MESSAGE)
            return
        }
        val target = scenarioPanel.bruteForceTarget() ?: return
        val wordlist = scenarioPanel.bruteForceWordlist() ?: return
        val cancelled = AtomicBoolean(false)
        activePreparation = cancelled
        pendingBruteUpdates.clear()
        completedBruteRows.clear()
        completedBruteCount.set(0)
        currentBruteChunk = 0
        processedBruteCount = 0
        bruteForceRunning = true
        analysisPanel.setRows(emptyList(), true)
        setRunning(true)
        statusLabel.text = bruteProgress("Running")
        bruteUpdateTimer.start()
        executor.execute {
            try {
                BruteForcePlanner.forEachChunk(target, wordlist, config.engine, cancelled::get) {
                        chunkNumber, processedCount, plans ->
                    if (cancelled.get()) throw CancellationException()
                    currentBruteChunk = chunkNumber
                    processedBruteCount = processedCount
                    onEdtAndWait {
                        analysisPanel.appendRows(plans.map { it.row }, true)
                        statusLabel.text = bruteProgress("Running")
                    }
                    val chunkConfig = config.copy(
                        startDelayMs = if (chunkNumber == 1) config.startDelayMs else 0,
                        batchDelayMs = 0,
                    )
                    val runner = ExperimentRunner(
                        chunkConfig,
                        plans.firstOrNull()?.endpoint.orEmpty(),
                        listOf(plans),
                        ::publishBruteUpdate,
                        {},
                        {},
                    )
                    activeRunner = runner
                    runner.run()
                    activeRunner = null
                    onEdtAndWait { flushBruteUpdates() }
                    if (cancelled.get()) throw CancellationException()
                }
                SwingUtilities.invokeLater { finishBruteForce("Completed") }
            } catch (_: CancellationException) {
                SwingUtilities.invokeLater { finishBruteForce("Cancelled") }
            } catch (failure: Throwable) {
                SwingUtilities.invokeLater {
                    finishBruteForce("Failed")
                    JOptionPane.showMessageDialog(
                        this, failure.message ?: failure.toString(), "Brute Force failed", JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun launchRunner(
        config: EngineConfig,
        batches: List<List<PlannedRequest>>,
        showPayload: Boolean,
    ) {
        activePreparation = null
        analysisPanel.setRows(batches.flatten().map { it.row }, showPayload)
        val runner = ExperimentRunner(
            config,
            batches.firstOrNull()?.firstOrNull()?.endpoint.orEmpty(),
            batches,
            { row -> SwingUtilities.invokeLater { analysisPanel.rowUpdated(row) } },
            { progress -> SwingUtilities.invokeLater { statusLabel.text = progress } },
            { message -> SwingUtilities.invokeLater { finishRun(message) } },
        )
        activeRunner = runner
        setRunning(true)
        statusLabel.text = "Starting"
        executor.execute(runner)
    }

    private fun cancelRun() {
        statusLabel.text = "Cancelling..."
        cancelButton.isEnabled = false
        activePreparation?.set(true)
        activeRunner?.let { runner -> executor.execute { runner.cancel() } }
    }

    private fun finishRun(message: String) {
        activeRunner = null
        activePreparation = null
        setRunning(false)
        statusLabel.text = message
    }

    private fun publishBruteUpdate(row: GuiResult) {
        pendingBruteUpdates.add(row)
        if (!row.isPending() && completedBruteRows.add(row)) completedBruteCount.incrementAndGet()
    }

    private fun flushBruteUpdates() {
        check(SwingUtilities.isEventDispatchThread())
        val changed = LinkedHashSet<GuiResult>()
        while (true) changed += pendingBruteUpdates.poll() ?: break
        if (changed.isNotEmpty()) analysisPanel.rowsUpdated(changed)
        if (bruteForceRunning) statusLabel.text = bruteProgress("Running")
    }

    private fun finishBruteForce(state: String) {
        flushBruteUpdates()
        bruteUpdateTimer.stop()
        bruteForceRunning = false
        activeRunner = null
        activePreparation = null
        setRunning(false)
        statusLabel.text = bruteProgress(state)
    }

    private fun bruteProgress(state: String): String =
        "Chunk $currentBruteChunk | Processed: $processedBruteCount | " +
            "Completed: ${completedBruteCount.get()} | $state"

    private fun onEdtAndWait(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }

    private fun restoreDefaults() {
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Reset all execution and engine settings to the Turbo Intruder defaults? " +
                "Requests, identities, and results will not be changed.",
            "Restore to Default",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
        if (choice == JOptionPane.YES_OPTION) {
            enginePanel.restoreDefaults()
            statusLabel.text = "Execution settings restored"
        }
    }

    private fun setRunning(running: Boolean) {
        startButton.isEnabled = !running
        cancelButton.isEnabled = running
        restoreButton.isEnabled = !running
        enginePanel.setControlsEnabled(!running)
        scenarioPanel.setControlsEnabled(!running)
    }

    private fun synchronizeMode() {
        val bruteForce = enginePanel.runMode() == GuiRunMode.BRUTE_FORCE
        scenarioPanel.setBruteForceMode(bruteForce)
        scenarioPanel.setReleaseOffsetsEnabled(!bruteForce && enginePanel.isLastByteSync())
    }

    companion object {
        const val WORKSPACE_TAB = "Workspace"
        const val ADVANCED_SETTINGS_TAB = "Advanced Settings"
        const val RESPONSES_TAB = "Responses"
    }
}

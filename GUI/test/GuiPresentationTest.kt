package burp.gui

import burp.EngineConfigPanel
import burp.RequestMappingPanel
import burp.RequestScenarioPanel
import burp.RequestScenarioModel
import burp.TurboGuiWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Color
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.JLabel

class GuiPresentationTest {
    @Test
    fun `mapping square renders number custom name and color without HTML markup`() {
        val model = RequestScenarioModel()
        val template = model.appendTemplate("Original", byteArrayOf(), null, null)
        val slot = model.slots.single()
        model.renameSlot(slot.id, "Verify <OTP>")
        model.colorSlot(slot.id, Color.ORANGE.rgb)
        val panel = RequestMappingPanel(model, {})
        panel.refresh(emptyList())
        val button = descendants(panel).filterIsInstance<JToggleButton>().single()

        assertTrue(button.text.startsWith("1  Verify <OTP>"))
        assertFalse(button.text.contains("<html>", ignoreCase = true))
        assertEquals(Color.ORANGE.rgb, button.background.rgb)
        assertEquals(template.id, slot.templateId)
    }

    @Test
    fun `edit selected request uses the stable active slot id`() {
        val model = RequestScenarioModel()
        val template = model.appendTemplate("Request", byteArrayOf(), null, null)
        model.updateTemplate(template.id, template.name, template.request, 2)
        var edited: Long? = null
        val panel = RequestMappingPanel(model, {}, { edited = it })
        val second = model.slots[1]

        panel.selectSlot(second.id)
        descendants(panel).filterIsInstance<JButton>()
            .single { it.text == "Edit Selected Request" }.doClick()

        assertEquals(second.id, edited)
    }

    @Test
    fun `dragging a request square updates model order and displayed positions`() {
        val model = RequestScenarioModel()
        (1..5).forEach { model.appendTemplate("Request $it", byteArrayOf(it.toByte()), null, null) }
        model.colorSlot(5, Color.ORANGE.rgb)
        val panel = RequestMappingPanel(model, {})
        panel.refresh(emptyList())
        panel.setSize(1_200, 200)
        panel.doLayout()
        val grid = descendants(panel).filterIsInstance<javax.swing.JPanel>()
            .single { it.layout is GridLayout }
        grid.setSize(1_100, 80)
        grid.doLayout()
        val originalButtons = grid.components.filterIsInstance<JToggleButton>()
        val dragged = originalButtons[4]
        val target = originalButtons[1]
        val press = Point(dragged.width / 2, dragged.height / 2)
        val targetInGrid = Point(target.x + 1, target.y + target.height / 2)
        val targetInDragged = javax.swing.SwingUtilities.convertPoint(grid, targetInGrid, dragged)

        dispatchMouse(dragged, MouseEvent.MOUSE_PRESSED, press)
        dispatchMouse(dragged, MouseEvent.MOUSE_DRAGGED, targetInDragged)
        dispatchMouse(dragged, MouseEvent.MOUSE_RELEASED, targetInDragged)

        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), model.slots.map { it.id })
        val reordered = grid.components.filterIsInstance<JToggleButton>()
        assertTrue(reordered[1].text.startsWith("2  Request 5"))
        assertEquals(Color.ORANGE.rgb, reordered[1].background.rgb)
        assertTrue(reordered.all { it.mouseMotionListeners.isNotEmpty() })
    }

    @Test
    fun `internal tab names preserve the requested workflow order`() {
        assertEquals(
            listOf("Workspace", "Advanced Settings", "Responses"),
            listOf(
                TurboGuiWorkspace.WORKSPACE_TAB,
                TurboGuiWorkspace.ADVANCED_SETTINGS_TAB,
                TurboGuiWorkspace.RESPONSES_TAB,
            ),
        )
        assertEquals("Request", RequestScenarioPanel.REQUEST_TAB)
    }

    @Test
    fun `advanced request settings are separate from workspace settings`() {
        val panel = EngineConfigPanel()
        val workspaceLabels = labels(panel)
        val advancedLabels = labels(panel.advancedRequestComponent() as Container)

        assertEquals(
            setOf("mode", "engine", "technique", "concurrentConnections", "requestsPerConnection", "pipeline"),
            workspaceLabels,
        )
        assertEquals(
            setOf(
                "batches", "maxQueueSize", "timeout", "maxRetriesPerRequest",
                "gateMode", "startDelayMs", "requestDelayMs", "batchDelayMs",
            ),
            advancedLabels,
        )
    }

    @Test
    fun `every engine setting control has an explanatory tooltip`() {
        val panel = EngineConfigPanel()
        val settings = descendants(panel) + descendants(panel.advancedRequestComponent() as Container)
        val controls = settings.filter {
            it is JComboBox<*> || it is JSpinner || it is JCheckBox
        }.map { it as javax.swing.JComponent }

        assertTrue(controls.size >= 14)
        assertTrue(controls.all { !it.toolTipText.isNullOrBlank() })
        assertTrue((labels(panel) + labels(panel.advancedRequestComponent() as Container)).containsAll(setOf(
            "mode", "engine", "technique", "batches", "concurrentConnections",
            "requestsPerConnection", "maxQueueSize", "timeout", "maxRetriesPerRequest", "pipeline",
            "gateMode", "startDelayMs", "requestDelayMs", "batchDelayMs",
        )))
    }

    @Test
    fun `advanced request controls still feed configuration and restore defaults`() {
        val panel = EngineConfigPanel()
        val advanced = panel.advancedRequestComponent() as Container
        spinner(advanced, "batches").value = 7
        spinner(advanced, "maxQueueSize").value = 345
        spinner(advanced, "timeout").value = 23
        spinner(advanced, "maxRetriesPerRequest").value = 4
        spinner(advanced, "startDelayMs").value = 11
        spinner(advanced, "requestDelayMs").value = 12
        spinner(advanced, "batchDelayMs").value = 13

        assertEquals(7, panel.batchCount())
        assertEquals(345, panel.config().maxQueueSize)
        assertEquals(23, panel.config().timeoutSeconds)
        assertEquals(4, panel.config().retries)
        assertEquals(11, panel.config().startDelayMs)
        assertEquals(12, panel.config().requestDelayMs)
        assertEquals(13, panel.config().batchDelayMs)

        panel.setControlsEnabled(false)
        assertTrue(descendants(advanced).filterIsInstance<JSpinner>().all { !it.isEnabled })
        panel.setControlsEnabled(true)
        panel.restoreDefaults()
        assertEquals(1, panel.batchCount())
        assertEquals(100, panel.config().maxQueueSize)
        assertEquals(10, panel.config().timeoutSeconds)
        assertEquals(3, panel.config().retries)
        assertEquals(0, panel.config().startDelayMs)
        assertEquals(0, panel.config().requestDelayMs)
        assertEquals(0, panel.config().batchDelayMs)
    }

    private fun labels(root: Container): Set<String> = descendants(root)
        .filterIsInstance<JLabel>().map { it.text.removeSuffix(":") }.toSet()

    private fun spinner(root: Container, label: String): JSpinner = descendants(root)
        .filterIsInstance<Container>()
        .single { container -> container.components.filterIsInstance<JLabel>().any { it.text == "$label:" } }
        .components.filterIsInstance<JSpinner>().single()

    private fun dispatchMouse(component: Component, eventId: Int, point: Point) {
        val released = eventId == MouseEvent.MOUSE_RELEASED
        component.dispatchEvent(MouseEvent(
            component, eventId, System.currentTimeMillis(), if (released) 0 else MouseEvent.BUTTON1_DOWN_MASK,
            point.x, point.y, 1, false,
            if (eventId == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1,
        ))
    }

    private fun descendants(root: Container): List<Component> = root.components.flatMap { component ->
        listOf(component) + if (component is Container) descendants(component) else emptyList()
    }
}

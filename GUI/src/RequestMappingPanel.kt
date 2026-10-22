package burp

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class RequestMappingPanel(
    private val model: RequestScenarioModel,
    private val onSlotSelected: (Long) -> Unit,
    private val onEditSelected: (Long) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val grid = JPanel(GridLayout(0, 12, 3, 3))
    private val mappingScroll = JScrollPane(grid)
    private val identityChoice = JComboBox<String>()
    private val releaseOffset = JSpinner(SpinnerNumberModel(0, 0, 3_600_000, 1))
    private val releaseOffsetLabel = JLabel("Release Offset (ms):")
    private val assignOffset = JButton("Assign offset")
    private val editSelected = JButton("Edit Selected Request")
    private val buttons = mutableMapOf<Long, JToggleButton>()
    private val selectedIds = linkedSetOf<Long>()
    private var activeSlotId: Long? = null
    private var releaseOffsetsAvailable = false
    private var controlsEnabled = true
    private var pressedSlotId: Long? = null
    private var dragOrigin: Point? = null
    private var draggedSlotId: Long? = null
    private var insertionIndex: Int? = null

    init {
        val assignIdentity = JButton("Assign identity")
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Identity:"))
            add(identityChoice)
            add(assignIdentity)
            add(releaseOffsetLabel)
            add(releaseOffset)
            add(assignOffset)
            add(editSelected)
        }, BorderLayout.NORTH)
        add(mappingScroll, BorderLayout.CENTER)
        assignIdentity.addActionListener {
            val identity = identityChoice.selectedItem?.toString()?.takeUnless { it == "Original" }
            model.assignIdentity(selectedIds, identity)
            refreshButtons()
        }
        assignOffset.addActionListener {
            model.assignReleaseOffset(selectedIds, releaseOffset.value as Int)
            refreshButtons()
        }
        editSelected.addActionListener { activeSlotId?.let(onEditSelected) }
        setReleaseOffsetsEnabled(false)
        refresh(emptyList())
    }

    fun refresh(identityNames: List<String>) {
        val previous = identityChoice.selectedItem?.toString()
        identityChoice.removeAllItems()
        identityChoice.addItem("Original")
        identityNames.distinct().forEach(identityChoice::addItem)
        if (previous != null) identityChoice.selectedItem = previous
        selectedIds.retainAll(model.slots.map { it.id }.toSet())
        if (activeSlotId !in model.slots.map { it.id }.toSet()) activeSlotId = null
        refreshButtons()
    }

    fun selectSlot(slotId: Long) {
        if (model.slot(slotId) == null) return
        activeSlotId = slotId
        selectedIds.clear()
        selectedIds += slotId
        refreshButtons()
        onSlotSelected(slotId)
    }

    fun setReleaseOffsetsEnabled(enabled: Boolean) {
        releaseOffsetsAvailable = enabled
        applyOffsetAvailability(enabled)
    }

    fun setControlsEnabled(enabled: Boolean) {
        controlsEnabled = enabled
        if (!enabled) cancelDrag()
        isEnabled = enabled
        identityChoice.isEnabled = enabled
        editSelected.isEnabled = enabled && activeSlotId != null
        buttons.values.forEach { it.isEnabled = enabled }
        applyOffsetAvailability(enabled && releaseOffsetsAvailable)
    }

    private fun refreshButtons() {
        grid.removeAll()
        buttons.clear()
        model.slots.forEachIndexed { index, slot ->
            val name = model.slotLabel(slot)
            val button = JToggleButton("${index + 1}  ${compact(name)}").apply {
                isSelected = slot.id in selectedIds
                toolTipText = "$name | ${slot.identityName ?: "Original"} | offset ${slot.releaseOffsetMs} ms"
                margin = java.awt.Insets(3, 5, 3, 5)
                preferredSize = java.awt.Dimension(88, 48)
                slot.colorRgb?.let { applyColor(this, Color(it)) }
                border = if (slot.id == activeSlotId) {
                    BorderFactory.createLineBorder(Color(60, 120, 210), 2)
                } else UIManagerDefaults.buttonBorder()
                addActionListener { event ->
                    val multiple = event.modifiers and ActionEvent.CTRL_MASK != 0
                    if (!multiple) {
                        selectedIds.clear()
                        selectedIds += slot.id
                    } else if (isSelected) selectedIds += slot.id else selectedIds -= slot.id
                    activeSlotId = slot.id
                    onSlotSelected(slot.id)
                    refreshButtons()
                }
                val dragListener = object : MouseAdapter() {
                    override fun mousePressed(event: MouseEvent) {
                        maybeShowMenu(event, slot.id)
                        if (controlsEnabled && SwingUtilities.isLeftMouseButton(event)) {
                            pressedSlotId = slot.id
                            dragOrigin = event.point
                        }
                    }

                    override fun mouseDragged(event: MouseEvent) = updateDrag(event, slot.id, this@apply)

                    override fun mouseReleased(event: MouseEvent) {
                        maybeShowMenu(event, slot.id)
                        if (draggedSlotId == slot.id) finishDrag(event, this@apply)
                        else clearDragState()
                    }
                }
                addMouseListener(dragListener)
                addMouseMotionListener(dragListener)
            }
            buttons[slot.id] = button
            grid.add(button)
        }
        grid.revalidate()
        grid.repaint()
        editSelected.isEnabled = isEnabled && activeSlotId != null
    }

    private fun updateDrag(event: MouseEvent, slotId: Long, button: JToggleButton) {
        if (!controlsEnabled || pressedSlotId != slotId) return
        if (draggedSlotId == null) {
            val origin = dragOrigin ?: return
            if (origin.distance(event.point) < DRAG_THRESHOLD) return
            draggedSlotId = slotId
            button.model.isArmed = false
            button.model.isPressed = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }
        val point = SwingUtilities.convertPoint(button, event.point, grid)
        insertionIndex = insertionIndexAt(point)
        showInsertionMarker()
        grid.scrollRectToVisible(Rectangle(point.x - 24, point.y - 24, 48, 48))
    }

    private fun finishDrag(event: MouseEvent, button: JToggleButton) {
        val slotId = draggedSlotId ?: return
        val point = SwingUtilities.convertPoint(button, event.point, grid)
        val target = insertionIndex ?: insertionIndexAt(point)
        model.moveSlotToInsertion(slotId, target)
        activeSlotId = slotId
        selectedIds.clear()
        selectedIds += slotId
        clearDragState()
        onSlotSelected(slotId)
        refreshButtons()
    }

    private fun insertionIndexAt(point: Point): Int {
        val components = grid.components
        if (components.isEmpty()) return 0
        val bounds = components.map { it.bounds }
        if (point.y < bounds.first().y) return 0
        if (point.y > bounds.last().y + bounds.last().height) return components.size
        val rowY = bounds.minBy { rectangle ->
            when {
                point.y < rectangle.y -> rectangle.y - point.y
                point.y > rectangle.y + rectangle.height -> point.y - rectangle.y - rectangle.height
                else -> 0
            }
        }.y
        val rowIndices = bounds.indices.filter { bounds[it].y == rowY }
        return rowIndices.firstOrNull { point.x < bounds[it].x + bounds[it].width / 2 }
            ?: (rowIndices.last() + 1)
    }

    private fun showInsertionMarker() {
        applyStandardBorders()
        val target = insertionIndex ?: return
        val markerColor = Color(0xF28C28)
        if (target < grid.componentCount) {
            val button = grid.getComponent(target) as JToggleButton
            button.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, markerColor), button.border,
            )
        } else if (grid.componentCount > 0) {
            val button = grid.getComponent(grid.componentCount - 1) as JToggleButton
            button.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 4, markerColor), button.border,
            )
        }
        grid.repaint()
    }

    private fun applyStandardBorders() {
        buttons.forEach { (slotId, button) ->
            button.border = if (slotId == activeSlotId) {
                BorderFactory.createLineBorder(Color(60, 120, 210), 2)
            } else UIManagerDefaults.buttonBorder()
        }
    }

    private fun cancelDrag() {
        applyStandardBorders()
        clearDragState()
    }

    private fun clearDragState() {
        pressedSlotId = null
        dragOrigin = null
        draggedSlotId = null
        insertionIndex = null
        cursor = Cursor.getDefaultCursor()
    }

    private fun maybeShowMenu(event: MouseEvent, slotId: Long) {
        if (!event.isPopupTrigger) return
        activeSlotId = slotId
        selectedIds.clear()
        selectedIds += slotId
        onSlotSelected(slotId)
        val slot = model.slot(slotId) ?: return
        JPopupMenu().apply {
            add("Rename").addActionListener {
                val name = JOptionPane.showInputDialog(
                    this@RequestMappingPanel,
                    "Request slot name:",
                    model.slotLabel(slot),
                )
                if (name != null) model.renameSlot(slotId, name)
                refreshButtons()
            }
            add("Change Color").addActionListener {
                val initial = slot.colorRgb?.let(::Color) ?: Color(120, 160, 220)
                JColorChooser.showDialog(this@RequestMappingPanel, "Request Color", initial)?.let {
                    model.colorSlot(slotId, it.rgb)
                }
                refreshButtons()
            }
            add("Clear Color").addActionListener { model.colorSlot(slotId, null); refreshButtons() }
            addSeparator()
            add("Move Up").apply {
                isEnabled = this@RequestMappingPanel.model.slots.indexOf(slot) > 0
            }.addActionListener {
                this@RequestMappingPanel.model.moveSlot(slotId, -1); refreshButtons()
            }
            add("Move Down").apply {
                isEnabled = this@RequestMappingPanel.model.slots.indexOf(slot) <
                    this@RequestMappingPanel.model.slots.lastIndex
            }.addActionListener { this@RequestMappingPanel.model.moveSlot(slotId, 1); refreshButtons() }
            show(event.component, event.x, event.y)
        }
    }

    private fun applyOffsetAvailability(enabled: Boolean) {
        releaseOffsetLabel.isEnabled = enabled
        releaseOffset.isEnabled = enabled
        assignOffset.isEnabled = enabled
        val message = if (enabled) "Best-effort last-byte gate offset" else
            "Available only for THREADED HTTP/1 Last-Byte Sync"
        releaseOffsetLabel.toolTipText = message
        releaseOffset.toolTipText = message
        assignOffset.toolTipText = message
    }

    private fun applyColor(button: JToggleButton, color: Color) {
        button.background = color
        button.foreground = if (color.red * 299 + color.green * 587 + color.blue * 114 > 140_000) {
            Color.BLACK
        } else Color.WHITE
        button.isOpaque = true
        button.isContentAreaFilled = true
    }

    private fun compact(value: String): String = if (value.length <= 18) value else value.take(17) + "…"

    private object UIManagerDefaults {
        fun buttonBorder() = javax.swing.UIManager.getBorder("ToggleButton.border")
    }

    companion object {
        private const val DRAG_THRESHOLD = 5.0
    }
}

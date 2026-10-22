package burp

import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Comparator
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.TableRowSorter

class ResultsAnalysisPanel : JPanel(BorderLayout()) {
    val model = GuiResultTableModel()
    private val duplicateHighlighting = DuplicateHighlighting()
    val table = object : JTable(model) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): Component {
            val component = super.prepareRenderer(renderer, row, column)
            if (!isRowSelected(row)) {
                val active = duplicateHighlighting.activeColumn
                val value = active?.let {
                    this@ResultsAnalysisPanel.model.valueAt(convertRowIndexToModel(row), it)?.toString().orEmpty()
                }
                component.background = value?.let { duplicateHighlighting.colorFor(it, background) } ?: background
            }
            return component
        }
    }
    private val requestEditor = Utils.callbacks.createMessageEditor(Controller(), false)
    private val responseEditor = Utils.callbacks.createMessageEditor(Controller(), false)
    private val metadata = JLabel("No result selected")
    private var openResponse: () -> Unit = {}
    private val responseView = JPanel(BorderLayout()).apply {
        add(metadata, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, requestEditor.component, responseEditor.component).apply {
            resizeWeight = 0.5
            dividerLocation = 500
        }, BorderLayout.CENTER)
    }
    private val responsesView by lazy {
        JSplitPane(JSplitPane.VERTICAL_SPLIT, this, responseView).apply {
            resizeWeight = 0.58
            dividerLocation = 430
        }
    }
    private val extractionName = JTextField(9)
    private val extractionType = JComboBox(ExtractionType.entries.toTypedArray())
    private val extractionExpression = JTextField(14)
    private val extractionListModel = javax.swing.DefaultListModel<String>()
    private val extractionList = JList(extractionListModel)
    private val extractions = mutableListOf<ExtractionDefinition>()
    private val filterColumn = JComboBox<String>()
    private val filterOperator = JComboBox(FilterOperator.entries.toTypedArray())
    private val filterValue = JTextField(10)
    private val filterListModel = javax.swing.DefaultListModel<String>()
    private val filterList = JList(filterListModel)
    private val filters = mutableListOf<ResultFilter>()
    private val groupColumn = JComboBox<String>()
    private val highlightColumn = JComboBox<String>()
    private val hiddenColumns = (GuiResultTableModel.BASE_COLUMNS - DEFAULT_VISIBLE_COLUMNS)
        .toMutableSet().apply { add("Group Count") }
    private val analysisControls = JPanel(BorderLayout())

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        val addExtraction = JButton("Add column")
        val removeExtraction = JButton("Remove column")
        toolbar.apply {
            add(JLabel("Custom:")); add(extractionName); add(extractionType); add(extractionExpression)
            add(addExtraction); add(removeExtraction)
        }
        val filtersBar = JPanel(FlowLayout(FlowLayout.LEFT))
        val addFilter = JButton("Add filter")
        val removeFilter = JButton("Remove filter")
        val clearFilters = JButton("Clear filters")
        val columnsButton = JButton("Columns")
        filtersBar.apply {
            add(JLabel("Filter:")); add(filterColumn); add(filterOperator); add(filterValue)
            add(addFilter); add(removeFilter); add(clearFilters)
            add(JLabel("Group by:")); add(groupColumn); add(columnsButton)
            add(JLabel("Highlight duplicates:")); add(highlightColumn)
        }
        val lists = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(extractionList),
            JScrollPane(filterList),
        ).apply {
            resizeWeight = 0.5
            preferredSize = java.awt.Dimension(400, 70)
        }
        analysisControls.apply {
            add(toolbar, BorderLayout.NORTH)
            add(filtersBar, BorderLayout.CENTER)
            add(lists, BorderLayout.SOUTH)
            isVisible = false
        }
        add(analysisControls, BorderLayout.NORTH)

        table.autoCreateRowSorter = true
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) showSelected()
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && table.rowAtPoint(event.point) >= 0) openResponse()
            }
        })
        add(JScrollPane(table), BorderLayout.CENTER)

        addExtraction.addActionListener { addExtraction() }
        removeExtraction.addActionListener { removeExtraction() }
        addFilter.addActionListener { addFilter() }
        removeFilter.addActionListener { removeFilter() }
        clearFilters.addActionListener { filters.clear(); filterListModel.clear(); applyFilters() }
        groupColumn.addActionListener { applyGrouping() }
        highlightColumn.addActionListener { applyHighlighting() }
        columnsButton.addActionListener { showColumnsMenu(columnsButton) }
        filterOperator.addActionListener {
            filterValue.isEnabled = filterOperator.selectedItem !in setOf(FilterOperator.EMPTY, FilterOperator.NOT_EMPTY)
        }
        refreshColumns()
    }

    fun setRows(rows: List<GuiResult>, showPayload: Boolean = false) {
        if (showPayload) hiddenColumns.remove("Payload")
        table.clearSelection()
        metadata.text = "No result selected"
        requestEditor.setMessage(ByteArray(0), true)
        responseEditor.setMessage(ByteArray(0), false)
        model.setRows(rows)
        if (rows.isNotEmpty()) {
            analysisControls.isVisible = true
            revalidate()
        }
        configureTable()
        updateHighlighting()
    }

    fun appendRows(rows: List<GuiResult>, showPayload: Boolean = false) {
        if (showPayload) hiddenColumns.remove("Payload")
        model.appendRows(rows)
        if (rows.isNotEmpty() && !analysisControls.isVisible) {
            analysisControls.isVisible = true
            revalidate()
        }
        applyColumnVisibility()
        updateHighlighting()
    }

    fun analysisControlsVisible(): Boolean = analysisControls.isVisible

    fun responsesComponent(): Component = responsesView

    fun onOpenResponse(action: () -> Unit) {
        openResponse = action
    }

    fun rowUpdated(result: GuiResult) {
        val index = model.indexOf(result)
        if (index >= 0) {
            model.rowUpdated(index)
            updateHighlighting()
        }
    }

    fun rowsUpdated(results: Collection<GuiResult>) {
        if (results.isEmpty()) return
        model.rowsUpdated(results)
        updateHighlighting()
    }

    private fun addExtraction() {
        val name = extractionName.text.trim()
        val expression = extractionExpression.text.trim()
        if (name.isEmpty() || expression.isEmpty() || model.columnNames().contains(name)) return
        extractions += ExtractionDefinition(name, extractionType.selectedItem as ExtractionType, expression)
        extractionListModel.addElement("$name = ${(extractionType.selectedItem as ExtractionType).displayName}: $expression")
        model.setExtractions(extractions)
        configureTable()
        highlightColumn.selectedItem = name
        extractionName.text = ""
        extractionExpression.text = ""
    }

    private fun removeExtraction() {
        val index = extractionList.selectedIndex
        if (index < 0) return
        val removed = extractions.removeAt(index)
        hiddenColumns.remove(removed.name)
        extractionListModel.remove(index)
        filters.removeIf { it.column == removed.name }
        rebuildFilterList()
        model.setExtractions(extractions)
        configureTable()
    }

    private fun addFilter() {
        val column = filterColumn.selectedItem?.toString() ?: return
        val operator = filterOperator.selectedItem as FilterOperator
        val value = if (operator in setOf(FilterOperator.EMPTY, FilterOperator.NOT_EMPTY)) "" else filterValue.text
        val filter = ResultFilter(column, operator, value)
        filters += filter
        filterListModel.addElement("$column ${operator.displayName}${if (value.isEmpty()) "" else " '$value'"}")
        applyFilters()
    }

    private fun removeFilter() {
        val index = filterList.selectedIndex
        if (index < 0) return
        filters.removeAt(index)
        filterListModel.remove(index)
        applyFilters()
    }

    private fun rebuildFilterList() {
        filterListModel.clear()
        filters.forEach { filterListModel.addElement("${it.column} ${it.operator.displayName} '${it.value}'") }
        applyFilters()
    }

    private fun configureTable() {
        table.autoCreateRowSorter = true
        val sorter = table.rowSorter as TableRowSorter<GuiResultTableModel>
        sorter.sortsOnUpdates = true
        val smartComparator = Comparator<Any?> { left, right ->
            val a = left?.toString().orEmpty()
            val b = right?.toString().orEmpty()
            val an = a.toDoubleOrNull()
            val bn = b.toDoubleOrNull()
            if (an != null && bn != null) an.compareTo(bn) else a.compareTo(b, ignoreCase = true)
        }
        model.columnNames().forEachIndexed { index, name ->
            if (name !in GuiResultTableModel.BASE_COLUMNS && name != "Group Count") {
                sorter.setComparator(index, smartComparator)
            }
        }
        refreshColumns()
        applyFilters()
        applyColumnVisibility()
    }

    private fun refreshColumns() {
        val selectedFilter = filterColumn.selectedItem?.toString()
        val selectedGroup = groupColumn.selectedItem?.toString()
        val selectedHighlight = highlightColumn.selectedItem?.toString()
        filterColumn.removeAllItems()
        model.columnNames().filter { it != "Group Count" }.forEach(filterColumn::addItem)
        groupColumn.removeAllItems()
        groupColumn.addItem("(None)")
        model.columnNames().filter { it != "Group Count" }.forEach(groupColumn::addItem)
        selectedFilter?.let { filterColumn.selectedItem = it }
        selectedGroup?.let { groupColumn.selectedItem = it }
        highlightColumn.removeAllItems()
        highlightColumn.addItem("(None)")
        extractions.forEach { highlightColumn.addItem(it.name) }
        if (selectedHighlight in extractions.map { it.name }) {
            highlightColumn.selectedItem = selectedHighlight
        }
    }

    private fun applyFilters() {
        val sorter = table.rowSorter as? TableRowSorter<GuiResultTableModel> ?: return
        sorter.rowFilter = object : RowFilter<GuiResultTableModel, Int>() {
            override fun include(entry: Entry<out GuiResultTableModel, out Int>): Boolean =
                model.matches(entry.identifier, filters)
        }
    }

    private fun applyGrouping() {
        val selected = groupColumn.selectedItem?.toString()?.takeUnless { it == "(None)" }
        if (selected == null) hiddenColumns.add("Group Count") else hiddenColumns.remove("Group Count")
        model.setGroupColumn(selected)
        val sorter = table.rowSorter as? TableRowSorter<GuiResultTableModel> ?: return
        if (selected != null) {
            val index = model.columnNames().indexOf(selected)
            if (index >= 0) sorter.sortKeys = listOf(RowSorter.SortKey(index, SortOrder.ASCENDING))
        }
        applyColumnVisibility()
    }

    private fun applyHighlighting() {
        duplicateHighlighting.activate(
            highlightColumn.selectedItem?.toString()?.takeUnless { it == "(None)" },
        )
        updateHighlighting()
    }

    private fun updateHighlighting() {
        val column = duplicateHighlighting.activeColumn
        val values = if (column == null) emptyList() else
            (0 until model.rowCount).map { model.valueAt(it, column)?.toString().orEmpty() }
        duplicateHighlighting.update(values)
        table.repaint()
    }

    private fun showColumnsMenu(button: JButton) {
        val menu = JPopupMenu()
        model.columnNames().forEach { name ->
            menu.add(JCheckBoxMenuItem(name, name !in hiddenColumns).apply {
                addActionListener {
                    if (isSelected) hiddenColumns.remove(name) else hiddenColumns.add(name)
                    applyColumnVisibility()
                }
            })
        }
        menu.show(button, 0, button.height)
    }

    private fun applyColumnVisibility() {
        val desired = model.columnNames().filter { it !in hiddenColumns }
        val current = table.columnModel.columns.toList()
        current.filter { it.headerValue.toString() !in desired }.forEach(table.columnModel::removeColumn)
        desired.forEach { name ->
            if ((0 until table.columnModel.columnCount).none { table.columnModel.getColumn(it).headerValue == name }) {
                val modelIndex = model.columnNames().indexOf(name)
                table.addColumn(javax.swing.table.TableColumn(modelIndex).apply { headerValue = name })
            }
        }
        (0 until table.columnModel.columnCount).forEach { index ->
            val column = table.columnModel.getColumn(index)
            column.preferredWidth = when (column.headerValue.toString()) {
                "Template", "Identity" -> 110
                "State / Error" -> 260
                else -> 90
            }
        }
    }

    private fun showSelected() {
        if (table.selectedRow < 0) return
        val modelRow = table.convertRowIndexToModel(table.selectedRow)
        val snapshot = model.resultAt(modelRow)?.snapshot() ?: return
        requestEditor.setMessage(snapshot.request, true)
        responseEditor.setMessage(snapshot.response ?: ByteArray(0), false)
        metadata.text = "Request ${snapshot.requestNumber} | Batch ${snapshot.batchId} | " +
            "${snapshot.label} | ${snapshot.identity} | ${snapshot.state.displayName}"
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val values = mutableListOf<T>()
        while (hasMoreElements()) values += nextElement()
        return values
    }

    private inner class Controller : IMessageEditorController {
        override fun getHttpService(): IHttpService? = selected()?.service
        override fun getRequest(): ByteArray? = selected()?.request
        override fun getResponse(): ByteArray? = selected()?.response
    }

    private fun selected(): GuiResultSnapshot? {
        if (table.selectedRow < 0) return null
        return model.resultAt(table.convertRowIndexToModel(table.selectedRow))?.snapshot()
    }

    companion object {
        val DEFAULT_VISIBLE_COLUMNS = listOf("Request", "BatchID", "Queue ID", "Status", "Length", "Words")
    }
}

package burp

import java.util.IdentityHashMap
import javax.swing.table.AbstractTableModel

enum class FilterOperator(val displayName: String) {
    EQUALS("Equals"), NOT_EQUALS("Not equals"), CONTAINS("Contains"),
    NOT_CONTAINS("Not contains"), REGEX("Regex match"), EMPTY("Empty"),
    NOT_EMPTY("Not empty"), GREATER("Greater than"), LESS("Less than");
    override fun toString(): String = displayName
}

data class ResultFilter(val column: String, val operator: FilterOperator, val value: String)

class GuiResultTableModel : AbstractTableModel() {
    private val rows = mutableListOf<GuiResult>()
    private val rowIndices = IdentityHashMap<GuiResult, Int>()
    private var extractions: List<ExtractionDefinition> = emptyList()
    private var groupColumn: String? = null
    private var groupCounts: Map<String, Int> = emptyMap()

    fun setRows(newRows: List<GuiResult>) {
        rows.clear()
        rows.addAll(newRows)
        rebuildRowIndices()
        rows.forEach(::extractValues)
        updateGroups()
        fireTableStructureChanged()
    }

    fun appendRows(newRows: List<GuiResult>) {
        if (newRows.isEmpty()) return
        val first = rows.size
        rows.addAll(newRows)
        newRows.forEachIndexed { offset, row -> rowIndices[row] = first + offset }
        newRows.forEach(::extractValues)
        if (groupColumn != null) {
            updateGroups()
            fireTableDataChanged()
        } else {
            fireTableRowsInserted(first, rows.lastIndex)
        }
    }

    fun setExtractions(definitions: List<ExtractionDefinition>) {
        extractions = definitions.toList()
        rows.forEach(::extractValues)
        updateGroups()
        fireTableStructureChanged()
    }

    fun setGroupColumn(column: String?) {
        groupColumn = column?.takeIf { it.isNotBlank() && it != "Group Count" }
        updateGroups()
        fireTableDataChanged()
    }

    fun columnNames(): List<String> = BASE_COLUMNS + extractions.map { it.name } + "Group Count"
    fun resultAt(modelRow: Int): GuiResult? = rows.getOrNull(modelRow)
    fun indexOf(result: GuiResult): Int = rowIndices[result] ?: -1

    fun rowUpdated(modelRow: Int) {
        rows.getOrNull(modelRow)?.let(::extractValues)
        if (groupColumn != null) {
            updateGroups()
            fireTableDataChanged()
        }
        else if (modelRow in rows.indices) fireTableRowsUpdated(modelRow, modelRow)
    }

    fun rowsUpdated(results: Collection<GuiResult>) {
        val indices = results.asSequence().map(::indexOf).filter { it >= 0 }.distinct().sorted().toList()
        if (indices.isEmpty()) return
        indices.forEach { extractValues(rows[it]) }
        if (groupColumn != null) {
            updateGroups()
            fireTableDataChanged()
        } else {
            contiguousRanges(indices).forEach { fireTableRowsUpdated(it.first, it.last) }
        }
    }

    fun valueAt(row: Int, columnName: String): Any? {
        val index = columnNames().indexOf(columnName)
        return if (index < 0) null else getValueAt(row, index)
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columnNames().size
    override fun getColumnName(column: Int): String = columnNames()[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (getColumnName(columnIndex)) {
        "Request", "BatchID", "Queue ID", "Status", "Length", "Words",
        "Response Order", "Anomaly Rank", "Release Offset (ms)", "Group Count" -> java.lang.Integer::class.java
        "Slot ID" -> java.lang.Long::class.java
        "TTFB (µs)", "TTLB (µs)", "Arrival (µs)", "Gate Opened (ms)" -> java.lang.Long::class.java
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? = valueFor(
        rows[rowIndex].tableSnapshot(), getColumnName(columnIndex), rowIndex,
    )

    fun matches(modelRow: Int, filters: List<ResultFilter>): Boolean = filters.all { filter ->
        val actual = valueAt(modelRow, filter.column)?.toString().orEmpty()
        when (filter.operator) {
            FilterOperator.EQUALS -> actual.equals(filter.value, ignoreCase = true)
            FilterOperator.NOT_EQUALS -> !actual.equals(filter.value, ignoreCase = true)
            FilterOperator.CONTAINS -> actual.contains(filter.value, ignoreCase = true)
            FilterOperator.NOT_CONTAINS -> !actual.contains(filter.value, ignoreCase = true)
            FilterOperator.REGEX -> runCatching { Regex(filter.value).containsMatchIn(actual) }.getOrDefault(false)
            FilterOperator.EMPTY -> actual.isEmpty()
            FilterOperator.NOT_EMPTY -> actual.isNotEmpty()
            FilterOperator.GREATER -> compareNumbers(actual, filter.value) { a, b -> a > b }
            FilterOperator.LESS -> compareNumbers(actual, filter.value) { a, b -> a < b }
        }
    }

    private fun valueFor(result: GuiResultSnapshot, name: String, rowIndex: Int): Any? = when (name) {
        "Request" -> result.requestNumber
        "BatchID" -> result.batchId
        "Queue ID" -> result.queueId
        "Payload" -> result.payload.orEmpty()
        "Template" -> result.label
        "Identity" -> result.identity
        "Status" -> result.status
        "Length" -> result.length
        "Words" -> result.words
        "TTFB (µs)" -> result.ttfb
        "TTLB (µs)" -> result.ttlb
        "Arrival (µs)" -> result.arrival
        "Response Order" -> result.responseOrder
        "Connection ID" -> result.connectionId ?: ""
        "Anomaly Rank" -> result.anomalyRank
        "State / Error" -> result.error?.let { "${result.state.displayName}: $it" } ?: result.state.displayName
        "Release Offset (ms)" -> result.releaseOffsetMs
        "Gate Opened (ms)" -> result.gateOpenedMs
        "Slot ID" -> result.slotId
        "Group Count" -> groupKey(rowIndex)?.let { groupCounts[it] ?: 0 } ?: 0
        else -> result.customValues[name].orEmpty()
    }

    private fun extractValues(row: GuiResult) {
        if (extractions.isEmpty()) return
        val response = row.snapshot().response
        row.setCustomValues(extractions.associate { it.name to ResponseExtractor.extract(it, response) })
    }

    private fun updateGroups() {
        groupCounts = rows.indices.mapNotNull(::groupKey).groupingBy { it }.eachCount()
    }

    private fun groupKey(rowIndex: Int): String? {
        val column = groupColumn ?: return null
        val result = rows.getOrNull(rowIndex)?.tableSnapshot() ?: return null
        return valueFor(result, column, rowIndex)?.toString().orEmpty()
    }

    private fun compareNumbers(left: String, right: String, predicate: (Double, Double) -> Boolean): Boolean {
        val a = left.toDoubleOrNull() ?: return false
        val b = right.toDoubleOrNull() ?: return false
        return predicate(a, b)
    }

    private fun contiguousRanges(indices: List<Int>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var first = indices.first()
        var last = first
        indices.drop(1).forEach { index ->
            if (index == last + 1) last = index
            else {
                ranges += first..last
                first = index
                last = index
            }
        }
        ranges += first..last
        return ranges
    }

    private fun rebuildRowIndices() {
        rowIndices.clear()
        rows.forEachIndexed { index, row -> rowIndices[row] = index }
    }

    companion object {
        val BASE_COLUMNS = listOf(
            "Request", "BatchID", "Queue ID", "Status", "Length", "Words",
            "Payload", "Template", "Identity", "TTFB (µs)", "TTLB (µs)", "Arrival (µs)",
            "Response Order", "Connection ID", "Anomaly Rank", "State / Error",
            "Release Offset (ms)", "Gate Opened (ms)",
            "Slot ID",
        )
    }
}

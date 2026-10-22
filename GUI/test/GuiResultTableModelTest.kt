package burp.gui

import burp.GuiResult
import burp.GuiResultTableModel
import burp.ExtractionDefinition
import burp.ExtractionType
import burp.FilterOperator
import burp.Request
import burp.ResultFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuiResultTableModelTest {
    @Test
    fun `exposes numeric column classes for table sorting`() {
        val model = GuiResultTableModel()

        assertEquals(java.lang.Integer::class.java, model.getColumnClass(0))
        assertEquals(java.lang.Integer::class.java, model.getColumnClass(3))
        assertEquals(java.lang.Long::class.java, model.getColumnClass(9))
        assertEquals(String::class.java, model.getColumnClass(15))
        assertEquals(java.lang.Integer::class.java, model.getColumnClass(16))
        assertEquals(java.lang.Long::class.java, model.getColumnClass(17))
        assertEquals(
            listOf("Request", "BatchID", "Queue ID", "Status", "Length", "Words"),
            burp.ResultsAnalysisPanel.DEFAULT_VISIBLE_COLUMNS,
        )
    }

    @Test
    fun `keeps all rows when individual rows change`() {
        val rows = (1..3).map {
            GuiResult(it, "Request A", "GET / HTTP/1.1\r\n\r\n".toByteArray())
        }
        val model = GuiResultTableModel()
        model.setRows(rows)
        rows[1].fail("timeout")
        model.rowUpdated(1)

        assertEquals(3, model.rowCount)
        assertEquals("Error: timeout", model.getValueAt(1, 15))
    }

    @Test
    fun `appends planned chunks and applies terminal updates without replacing prior rows`() {
        val firstChunk = (1..1_000).map { GuiResult(it, "Fuzz", "GET /$it HTTP/1.1\r\n\r\n".toByteArray()) }
        val finalRow = GuiResult(1_001, "Fuzz", "GET /1001 HTTP/1.1\r\n\r\n".toByteArray())
        val model = GuiResultTableModel()

        model.appendRows(firstChunk)
        model.appendRows(listOf(finalRow))
        firstChunk[999].fail("timeout")
        finalRow.cancel()
        model.rowsUpdated(listOf(firstChunk[999], finalRow))

        assertEquals(1_001, model.rowCount)
        assertEquals(1_001, model.getValueAt(1_000, 0))
        assertEquals("Error: timeout", model.getValueAt(999, 15))
        assertEquals("Cancelled", model.getValueAt(1_000, 15))
    }

    @Test
    fun `custom columns filter numerically without deleting raw rows`() {
        val first = completed(1, "{\"data\":{\"balance\":500}}")
        val second = completed(2, "{\"data\":{\"balance\":20}}")
        val model = GuiResultTableModel()
        model.setRows(listOf(first, second))
        model.setExtractions(listOf(ExtractionDefinition("Balance", ExtractionType.JSON, "data.balance")))

        assertEquals("500", model.valueAt(0, "Balance"))
        assertEquals(true, model.matches(0, listOf(ResultFilter("Balance", FilterOperator.GREATER, "100"))))
        assertEquals(false, model.matches(1, listOf(ResultFilter("Balance", FilterOperator.GREATER, "100"))))
        assertEquals(2, model.rowCount)
    }

    @Test
    fun `group count identifies duplicate extracted values`() {
        val model = GuiResultTableModel()
        model.setRows(listOf(
            completed(1, "{\"id\":\"same\"}"),
            completed(2, "{\"id\":\"same\"}"),
            completed(3, "{\"id\":\"different\"}"),
        ))
        model.setExtractions(listOf(ExtractionDefinition("RequestID", ExtractionType.JSON, "id")))
        model.setGroupColumn("RequestID")

        assertEquals(2, model.valueAt(0, "Group Count"))
        assertEquals(2, model.valueAt(1, "Group Count"))
        assertEquals(1, model.valueAt(2, "Group Count"))
        assertEquals(3, model.rowCount)
    }

    private fun completed(number: Int, body: String): GuiResult {
        val raw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        return GuiResult(number, "Request A", raw.toByteArray()).apply {
            complete(Request(raw).also {
                it.id = number
                it.response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$body"
            })
        }
    }
}

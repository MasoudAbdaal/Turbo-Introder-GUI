package burp.gui

import burp.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestExecutionPlannerTest {
    @Test
    fun `mapping order is the immutable queue order across repeated batches`() {
        val model = fiveSlotModel()
        val desired = listOf(5, 1, 3, 2, 4)
        reorder(model, desired)

        val batches = RequestExecutionPlanner.createBatches(
            model, emptyList(), 20, GuiEngine.THREADED, true,
        )
        model.moveSlot(model.slots.first().id, 1)

        assertEquals(20, batches.size)
        batches.forEach { batch ->
            assertEquals(desired.map(Int::toLong), batch.map { it.slotId })
            assertEquals(desired.map { "Request $it" }, batch.map { it.label })
        }
        assertEquals((1..100).toList(), batches.flatten().map { it.row.snapshot().requestNumber })
    }

    @Test
    fun `snapshot preserves slot request identity name color and release offset`() {
        val model = RequestScenarioModel()
        val template = model.appendTemplate("Login", request("GET", "/original", "old"), null, null)
        model.updateTemplate(template.id, template.name, template.request, 2)
        val slot = model.slots[1]
        model.updateSlotRequest(slot.id, request("POST", "/verify", "old", "otp=123"))
        model.renameSlot(slot.id, "Verify OTP")
        model.colorSlot(slot.id, 0x00ff8800)
        model.assignIdentity(setOf(slot.id), "User B")
        model.assignReleaseOffset(setOf(slot.id), 50)

        val plans = RequestExecutionPlanner.createBatches(
            model,
            listOf(IdentityConfig("User B", "Authorization: Bearer replacement\nX-Identity: user-b")),
            1,
            GuiEngine.THREADED,
            true,
        ).single()
        val plan = plans[1]

        assertEquals(slot.id, plan.slotId)
        assertEquals(slot.id, plan.row.snapshot().slotId)
        assertEquals("Verify OTP", plan.label)
        assertEquals(0x00ff8800, plan.colorRgb)
        assertEquals(50, plan.releaseOffsetMs)
        assertEquals("User B", plan.row.snapshot().identity)
        assertTrue(plan.request.startsWith("POST /verify HTTP/1.1"))
        assertTrue(plan.request.contains("Authorization: Bearer replacement"))
        assertTrue(plan.request.contains("X-Identity: user-b"))
        assertTrue(plan.request.endsWith("otp=123"))
        assertFalse(plan.request.contains("Authorization: old"))
    }

    private fun fiveSlotModel() = RequestScenarioModel().apply {
        (1..5).forEach { appendTemplate("Request $it", request("GET", "/$it", "token-$it"), null, null) }
    }

    private fun reorder(model: RequestScenarioModel, desired: List<Int>) {
        desired.forEachIndexed { targetIndex, number ->
            val id = model.slots.single { model.slotLabel(it) == "Request $number" }.id
            while (model.slots.indexOfFirst { it.id == id } > targetIndex) model.moveSlot(id, -1)
        }
    }

    private fun request(method: String, path: String, auth: String, body: String = ""): ByteArray {
        val contentLength = if (body.isEmpty()) "" else "Content-Length: ${body.length}\r\n"
        return ("$method $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: $auth\r\n" +
            contentLength + "\r\n$body").toByteArray(Charsets.ISO_8859_1)
    }
}

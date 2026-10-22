package burp.gui

import burp.RequestScenarioModel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestScenarioModelTest {
    @Test
    fun `slot request edits are isolated and survive template changes`() {
        val model = RequestScenarioModel()
        val template = model.appendTemplate("Transfer", bytes("original"), null, null)
        model.updateTemplate(template.id, template.name, bytes("original"), 3)
        val second = model.slots[1]

        model.updateSlotRequest(second.id, bytes("custom second"))
        model.updateTemplate(template.id, "Transfer", bytes("new template"), 3)

        assertArrayEquals(bytes("new template"), model.slots[0].request)
        assertArrayEquals(bytes("custom second"), model.slots[1].request)
        assertArrayEquals(bytes("new template"), model.slots[2].request)
        assertTrue(model.slots[1].customizedRequest)
    }

    @Test
    fun `slot names colors identity and offsets remain attached to stable ids`() {
        val model = RequestScenarioModel()
        val template = model.appendTemplate("Request A", bytes("a"), null, null)
        model.updateTemplate(template.id, template.name, template.request, 2)
        val slot = model.slots[1]
        model.renameSlot(slot.id, "Verify OTP")
        model.colorSlot(slot.id, 0x00ff8800)
        model.assignIdentity(setOf(slot.id), "User 2")
        model.assignReleaseOffset(setOf(slot.id), 50)

        assertTrue(model.moveSlot(slot.id, -1))
        assertEquals(slot.id, model.slots.first().id)
        assertEquals("Verify OTP", model.slotLabel(model.slots.first()))
        assertEquals(0x00ff8800, model.slots.first().colorRgb)
        assertEquals("User 2", model.slots.first().identityName)
        assertEquals(50, model.slots.first().releaseOffsetMs)
    }

    @Test
    fun `shrinking one template removes only its excess slots`() {
        val model = RequestScenarioModel()
        val first = model.appendTemplate("First", bytes("a"), null, null)
        val second = model.appendTemplate("Second", bytes("b"), null, null)
        model.updateTemplate(first.id, first.name, first.request, 3)
        val secondId = model.slots.single { it.templateId == second.id }.id

        model.updateTemplate(first.id, first.name, first.request, 1)

        assertEquals(2, model.slots.size)
        assertEquals(secondId, model.slots.single { it.templateId == second.id }.id)
        assertFalse(model.moveSlot(model.slots.first().id, -1))
        assertNull(model.slot(-1))
    }

    @Test
    fun `indexed reorder moves the complete stable slot and shifts surrounding slots`() {
        val model = RequestScenarioModel()
        (1..5).forEach { model.appendTemplate("Request $it", bytes("request-$it"), null, null) }
        val moved = model.slots.last()
        model.updateSlotRequest(moved.id, bytes("custom-five"))
        model.renameSlot(moved.id, "Custom Five")
        model.colorSlot(moved.id, 0x00123456)
        model.assignIdentity(setOf(moved.id), "Identity Five")
        model.assignReleaseOffset(setOf(moved.id), 100)

        assertTrue(model.moveSlotToInsertion(moved.id, 1))

        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), model.slots.map { it.id })
        val relocated = model.slots[1]
        assertEquals(moved.id, relocated.id)
        assertArrayEquals(bytes("custom-five"), relocated.request)
        assertEquals("Custom Five", model.slotLabel(relocated))
        assertEquals(0x00123456, relocated.colorRgb)
        assertEquals("Identity Five", relocated.identityName)
        assertEquals(100, relocated.releaseOffsetMs)
        assertTrue(relocated.customizedRequest)
    }

    @Test
    fun `indexed reorder remains valid after templates are removed and added`() {
        val model = RequestScenarioModel()
        val first = model.appendTemplate("First", bytes("first"), null, null)
        val removed = model.appendTemplate("Removed", bytes("removed"), null, null)
        val third = model.appendTemplate("Third", bytes("third"), null, null)
        model.removeTemplate(removed.id)
        val added = model.appendTemplate("Added", bytes("added"), null, null)
        val addedSlot = model.slots.single { it.templateId == added.id }

        assertTrue(model.moveSlotToInsertion(addedSlot.id, 0))
        assertEquals(
            listOf(added.id, first.id, third.id),
            model.slots.map { it.templateId },
        )
        assertEquals(3, model.slots.map { it.id }.toSet().size)
        assertEquals(listOf("added", "first", "third"), model.slots.map { String(it.request) })
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.ISO_8859_1)
}

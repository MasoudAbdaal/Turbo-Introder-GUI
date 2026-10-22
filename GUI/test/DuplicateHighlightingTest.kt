package burp.gui

import burp.DuplicateHighlighting
import java.awt.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DuplicateHighlightingTest {
    @Test
    fun `highlights only nonempty duplicate values with stable group colors`() {
        val highlighting = DuplicateHighlighting()
        val background = Color(240, 240, 240)
        highlighting.activate("ServiceID")
        highlighting.update(listOf("ABC", "XYZ", "ABC", "", "UNIQUE", "XYZ"))

        val abc = highlighting.colorFor("ABC", background)
        val xyz = highlighting.colorFor("XYZ", background)
        assertEquals(abc, highlighting.colorFor("ABC", background))
        assertNotEquals(abc, xyz)
        assertNull(highlighting.colorFor("UNIQUE", background))
        assertNull(highlighting.colorFor("", background))

        highlighting.update(listOf("XYZ", "ABC", "ABC", "XYZ"))
        assertEquals(abc, highlighting.colorFor("ABC", background))
        assertEquals(xyz, highlighting.colorFor("XYZ", background))
    }

    @Test
    fun `new results can turn a unique value into a highlighted duplicate`() {
        val highlighting = DuplicateHighlighting()
        highlighting.activate("Token")
        highlighting.update(listOf("same"))
        assertNull(highlighting.colorFor("same", Color.WHITE))

        highlighting.update(listOf("same", "same"))
        assertEquals(false, highlighting.colorFor("same", Color.WHITE) == null)
    }

    @Test
    fun `blending uses exactly thirty percent highlight color`() {
        val result = DuplicateHighlighting.blend(Color(200, 100, 50), Color(100, 200, 250))
        assertEquals(Color(170, 130, 110), result)
        assertEquals(0.30, DuplicateHighlighting.HIGHLIGHT_ALPHA)
    }
}

package burp

import java.awt.Color
import kotlin.math.roundToInt

class DuplicateHighlighting(
    private val palette: List<Color> = DEFAULT_PALETTE,
) {
    private val colorIndexes = mutableMapOf<String, MutableMap<String, Int>>()
    private var duplicateValues = emptySet<String>()
    var activeColumn: String? = null
        private set

    fun activate(column: String?) {
        activeColumn = column
        duplicateValues = emptySet()
    }

    fun update(values: List<String>) {
        val column = activeColumn ?: run { duplicateValues = emptySet(); return }
        val counts = values.filter(String::isNotEmpty).groupingBy { it }.eachCount()
        duplicateValues = counts.filterValues { it > 1 }.keys
        val indexes = colorIndexes.getOrPut(column) { linkedMapOf() }
        duplicateValues.forEach { value -> indexes.getOrPut(value) { indexes.size % palette.size } }
    }

    fun colorFor(value: String, normalBackground: Color): Color? {
        if (value !in duplicateValues) return null
        val column = activeColumn ?: return null
        val index = colorIndexes[column]?.get(value) ?: return null
        return blend(normalBackground, palette[index], HIGHLIGHT_ALPHA)
    }

    companion object {
        const val HIGHLIGHT_ALPHA = 0.30
        val DEFAULT_PALETTE = listOf(
            Color(70, 130, 180),
            Color(230, 145, 55),
            Color(75, 165, 105),
            Color(155, 105, 190),
            Color(205, 90, 105),
            Color(70, 165, 175),
            Color(185, 155, 60),
            Color(120, 135, 205),
        )

        fun blend(background: Color, highlight: Color, alpha: Double = HIGHLIGHT_ALPHA): Color {
            require(alpha in 0.0..1.0)
            fun channel(base: Int, overlay: Int) =
                (alpha * overlay + (1.0 - alpha) * base).roundToInt().coerceIn(0, 255)
            return Color(
                channel(background.red, highlight.red),
                channel(background.green, highlight.green),
                channel(background.blue, highlight.blue),
            )
        }
    }
}

package burp

import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel

class BruteForceControls(
    private val addPosition: () -> Unit,
    private val clearPositions: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)) {
    private val choose = JButton("Select Wordlist")
    private val addButton = JButton("Add Payload Position")
    private val clear = JButton("Clear Payload Positions")
    private val selected = JLabel("No wordlist selected")
    private var path: Path? = null

    init {
        add(choose); add(selected); add(addButton); add(clear)
        choose.toolTipText = "Select a UTF-8 text file; each line becomes one payload."
        addButton.toolTipText = "Wrap the current request-editor selection in § payload markers."
        clear.toolTipText = "Remove all § payload markers from the current request."
        choose.addActionListener {
            JFileChooser().apply {
                dialogTitle = "Select Brute Force Wordlist"
                if (showOpenDialog(this@BruteForceControls) == JFileChooser.APPROVE_OPTION) {
                    path = selectedFile.toPath()
                    selected.text = selectedFile.name
                    selected.toolTipText = selectedFile.absolutePath
                }
            }
        }
        addButton.addActionListener { addPosition() }
        clear.addActionListener { clearPositions() }
        isVisible = false
    }

    fun wordlist(): Path? = path

    fun validationError(): String? = when {
        path == null -> "Select a wordlist file."
        !Files.isRegularFile(path) || !Files.isReadable(path) -> "The selected wordlist is missing or unreadable."
        else -> null
    }
}

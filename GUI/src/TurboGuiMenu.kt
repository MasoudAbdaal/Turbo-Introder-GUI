package burp

import java.util.ArrayList
import javax.swing.JMenuItem

class TurboGuiMenu : IContextMenuFactory {
    override fun createMenuItems(invocation: IContextMenuInvocation?): MutableList<JMenuItem> {
        val selected = invocation?.selectedMessages?.filter { it.httpService != null }.orEmpty()
        if (selected.isEmpty()) return mutableListOf()

        val item = JMenuItem("Add to Turbo Intruder GUI")
        item.addActionListener { TurboGuiController.importRequests(selected) }
        return ArrayList<JMenuItem>().apply { add(item) }
    }
}

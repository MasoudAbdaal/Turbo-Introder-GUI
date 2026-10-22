package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import javax.swing.SwingUtilities

object TurboGuiController {
    private val lock = Any()
    private val pendingRequests = mutableListOf<IHttpRequestResponse>()
    private var api: MontoyaApi? = null
    private var workspace: TurboGuiWorkspace? = null
    private var registration: Registration? = null
    private var creating = false

    fun register(api: MontoyaApi) {
        synchronized(lock) { this.api = api }
        createWorkspaceIfReady()
    }

    fun callbacksReady() {
        createWorkspaceIfReady()
    }

    private fun createWorkspaceIfReady() {
        val currentApi = synchronized(lock) {
            val available = api
            if (available == null || workspace != null || creating || !Utils.gotBurp) return
            creating = true
            available
        }
        try {
            runOnEdtAndWait {
                val panel = TurboGuiWorkspace()
                val tabRegistration = currentApi.userInterface()
                    .registerSuiteTab("Turbo Intruder GUI", panel)
                val pending = synchronized(lock) {
                    workspace = panel
                    registration = tabRegistration
                    creating = false
                    pendingRequests.toList().also { pendingRequests.clear() }
                }
                pending.forEach(panel::appendRequest)
            }
        } catch (failure: Throwable) {
            synchronized(lock) { creating = false }
            throw failure
        }
    }

    fun importRequests(requests: List<IHttpRequestResponse>) {
        val saved = requests.map(Utils.callbacks::saveBuffersToTempFiles)
        SwingUtilities.invokeLater {
            val panel = synchronized(lock) {
                workspace ?: run {
                    pendingRequests += saved
                    null
                }
            }
            panel?.let { saved.forEach(it::appendRequest) }
        }
    }

    fun shutdown() {
        val (panel, tabRegistration) = synchronized(lock) {
            val values = workspace to registration
            pendingRequests.clear()
            api = null
            registration = null
            workspace = null
            creating = false
            values
        }
        runOnEdtAndWait {
            panel?.shutdown()
            tabRegistration?.deregister()
        }
    }

    private fun runOnEdtAndWait(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
}

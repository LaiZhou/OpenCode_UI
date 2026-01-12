package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.util.PortFinder
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-scoped service for managing OpenCode terminal and API integration.
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    companion object {
        private const val TERMINAL_TOOLWINDOW_ID = "Terminal"
        private const val OPEN_CODE_TAB_NAME = "OpenCode"
        private const val CONNECTION_RETRY_INTERVAL_MS = 5000L
    }

    private var port: Int? = null
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null

    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null

    fun getPort(): Int? = port
    fun getApiClient(): OpenCodeApiClient? = apiClient
    fun isConnected(): Boolean = isConnected.get()

    val sessionManager: SessionManager
        get() = project.service()

    private val diffViewerService: DiffViewerService
        get() = project.service()

    fun hasOpenCodeTerminal(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return false
        return toolWindow.contentManager.contents.any { it.displayName == OPEN_CODE_TAB_NAME }
    }

    fun focusOrCreateTerminal() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return
        val contentManager = toolWindow.contentManager
        val existingContent = contentManager.contents.find { it.displayName == OPEN_CODE_TAB_NAME }

        if (existingContent != null) {
            contentManager.setSelectedContent(existingContent)
            toolWindow.activate(null)
            startConnectionManager()
        } else {
            AppExecutorUtil.getAppExecutorService().submit {
                try {
                    val foundPort = PortFinder.findAvailablePort()
                    port = foundPort
                    logger.info("OpenCode selected port: $foundPort")

                    ApplicationManager.getApplication().invokeLater {
                        createTerminalAndConnect(foundPort)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to find available port", e)
                }
            }
        }
    }

    private fun createTerminalAndConnect(foundPort: Int) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return
        val terminalView = TerminalView.getInstance(project)
        val contentManager = toolWindow.contentManager

        val widget = terminalView.createLocalShellWidget(project.basePath, OPEN_CODE_TAB_NAME)
        val newContent = contentManager.contents.lastOrNull()
        newContent?.displayName = OPEN_CODE_TAB_NAME
        newContent?.let { contentManager.setSelectedContent(it) }

        val command = "opencode --hostname 0.0.0.0 --port $foundPort"
        widget.executeCommand(command)
        toolWindow.activate(null)

        initializeApiClient(foundPort)
        startConnectionManager()
    }

    private fun initializeApiClient(port: Int) {
        this.port = port
        val client = OpenCodeApiClient(port)
        apiClient = client
        sessionManager.setApiClient(client)
        logger.info("API client initialized on port $port")
    }

    @Synchronized
    private fun startConnectionManager() {
        if (connectionManagerTask != null && !connectionManagerTask!!.isDone) return
        
        val currentPort = port ?: return
        if (apiClient == null) initializeApiClient(currentPort)

        logger.debug("Starting connection manager (5s initial delay)")

        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (project.isDisposed) return@scheduleWithFixedDelay
            tryConnect()
        }, 5000, CONNECTION_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect() {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return

        try {
            val client = apiClient ?: return
            val projectPath = project.basePath ?: return
            
            logger.debug("Attempting to connect to OpenCode server on port $port...")

            if (client.checkHealth(projectPath)) {
                logger.debug("Server is healthy. Connecting to SSE...")
                sessionManager.refreshActiveSession()
                connectToSse()
            } else {
                logger.debug("Server not ready on port $port. Retrying in 5s...")
            }
        } catch (e: Exception) {
            logger.debug("Connection error to port $port: ${e.message}")
        } finally {
            isConnecting.set(false)
        }
    }

    private fun connectToSse() {
        val client = apiClient ?: return
        val projectPath = project.basePath ?: return

        sseListener?.disconnect()
        sseListener = client.createEventListener(
            directory = projectPath,
            onEvent = { event -> handleEvent(event) },
            onError = { error ->
                logger.debug("SSE error: ${error.message}")
                updateConnectionState(false)
            },
            onConnected = {
                logger.info("SSE connected")
                updateConnectionState(true)
                sessionManager.refreshActiveSession()
            },
            onDisconnected = {
                logger.debug("SSE disconnected")
                updateConnectionState(false)
            }
        )
        sseListener?.connect()
    }

    private fun updateConnectionState(connected: Boolean) {
        if (isConnected.getAndSet(connected) != connected) {
            logger.info("Connection state changed: connected=$connected")
            connectionListeners.forEach { it(connected) }
        }
    }

    private fun handleEvent(event: OpenCodeEvent) {
        logger.debug("Received SSE event: ${event.type}")
        when (event) {
            is SessionDiffEvent -> {
                val diffBatch = event.properties.toDiffBatch()
                logger.debug("Received ${diffBatch.diffs.size} diffs via SSE")
                sessionManager.onDiffReceived(diffBatch)
            }
            is SessionStatusEvent -> {
                logger.debug("Session status: ${event.properties.status.type}")
                sessionManager.onSessionStatusChanged(event.properties.sessionID, event.properties.status)
            }
            is SessionIdleEvent -> {
                logger.info("Session idle. Fetching latest diffs...")
                fetchAndShowDiffs(event.properties.sessionID)
            }
            is FileEditedEvent -> {
                logger.info("File edited: ${event.properties.file}")
                sessionManager.onFileEdited(event.properties.file)
            }
            is SessionUpdatedEvent -> logger.debug("Session updated: ${event.properties.info.id}")
            is MessagePartUpdatedEvent -> { /* No-op, handled by diff events */ }
            is MessagePartRemovedEvent -> logger.debug("Part removed: ${event.properties.partID}")
            is UnknownEvent -> logger.debug("Unknown event: ${event.type}")
        }
    }

    private fun fetchAndShowDiffs(sessionId: String, attempt: Int = 1) {
        val client = apiClient ?: return
        val projectPath = project.basePath ?: return

        AppExecutorUtil.getAppExecutorService().submit {
            try {
                logger.debug("Fetching diffs (Attempt $attempt): session=$sessionId")
                
                // Fetch diffs directly from server
                // We don't need messageID anymore as we use local git operations for accept/reject
                val diffs = client.getSessionDiff(sessionId, projectPath)
                
                if (diffs.isEmpty()) {
                    // Check session summary as fallback for diffs
                    val session = client.getSession(sessionId, projectPath)
                    val summaryDiffs = session?.summary?.diffs
                    
                    if (!summaryDiffs.isNullOrEmpty()) {
                        logger.debug("Found ${summaryDiffs.size} diffs in Session.summary")
                        processDiffs(sessionId, summaryDiffs)
                        return@submit
                    }

                    if (attempt < 3) {
                        val nextDelay = if (attempt == 1) 2000L else 3000L
                        logger.debug("No diffs found yet. Retrying in ${nextDelay}ms... (Attempt $attempt/3)")
                        
                        AppExecutorUtil.getAppScheduledExecutorService().schedule({
                            fetchAndShowDiffs(sessionId, attempt + 1)
                        }, nextDelay, TimeUnit.MILLISECONDS)
                    } else {
                        logger.debug("No diffs found after 3 attempts for session $sessionId")
                    }
                    return@submit
                }

                logger.info("Successfully fetched ${diffs.size} diffs")
                processDiffs(sessionId, diffs)

            } catch (e: Exception) {
                logger.error("Failed to fetch diffs for session $sessionId", e)
            }
        }
    }

    private fun processDiffs(sessionId: String, diffs: List<FileDiff>) {
        val entries = diffs.map { DiffEntry(sessionId, null, null, it, System.currentTimeMillis()) }
        sessionManager.onDiffReceived(DiffBatch(sessionId, null, diffs))

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) diffViewerService.showMultiFileDiff(entries)
        }
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
        listener(isConnected.get())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.remove(listener)
    }

    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return false
        val terminalView = TerminalView.getInstance(project)
        val contentManager = toolWindow.contentManager
        val openCodeContent = toolWindow.contentManager.contents.find { it.displayName == OPEN_CODE_TAB_NAME } ?: return false

        val widget = terminalView.getWidgets().firstOrNull { 
            toolWindow.contentManager.getContent(it.component) == openCodeContent 
        } as? ShellTerminalWidget ?: return false

        widget.ttyConnector?.let { tty -> text.forEach { tty.write(it.toString()) } }
        toolWindow.contentManager.setSelectedContent(openCodeContent)
        toolWindow.activate(null)
        return true
    }

    fun focusOrCreateTerminalAndPaste(text: String, attempts: Int = 20, delayMs: Long = 100L) {
        if (text.isBlank() || project.isDisposed) return
        focusOrCreateTerminal()
        schedulePasteAttempt(text, attempts, delayMs)
    }

    private fun schedulePasteAttempt(text: String, attemptsLeft: Int, delayMs: Long) {
        if (attemptsLeft <= 0 || project.isDisposed) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && !pasteToTerminal(text)) {
                    schedulePasteAttempt(text, attemptsLeft - 1, delayMs)
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun dispose() {
        connectionManagerTask?.cancel(true)
        sseListener?.disconnect()
        connectionListeners.clear()
        isConnected.set(false)
    }
}

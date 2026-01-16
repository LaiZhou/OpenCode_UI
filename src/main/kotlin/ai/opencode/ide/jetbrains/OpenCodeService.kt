package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditor
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditorProvider
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLinkFilter
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog
import ai.opencode.ide.jetbrains.util.PortFinder
import ai.opencode.ide.jetbrains.util.ProcessAuthDetector
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.TerminalView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx

/**
 * Project-scoped service for managing OpenCode terminal and API integration.
 * 
 * ## Connection Modes
 * - **Local Mode**: Terminal UI + opencode CLI process (localhost connections)
 * - **Headless Mode**: API-only connection, no local terminal (remote connections)
 * 
 * ## Key Behaviors
 * - `interactive=true`: User clicked icon/shortcut, always show UI feedback
 * - `interactive=false`: Background operation (e.g., sending context), prefer silent recovery
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    companion object {
        private const val OPEN_CODE_TAB_NAME = "OpenCode"
        private const val CONNECTION_RETRY_INTERVAL_MS = 5000L
    }

    // ==================== State ====================
    
    // Connection Configuration (persisted across reconnects until explicit reset)
    private var hostname: String = "127.0.0.1"
    private var port: Int? = null
    private var username: String? = null
    private var password: String? = null
    
    // Runtime State
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    
    // UI State (null in Headless mode)
    private var terminalVirtualFile: OpenCodeTerminalVirtualFile? = null
    private var terminalEditor: OpenCodeTerminalFileEditor? = null

    // Background Tasks & Listeners
    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null

    init {
        setupEditorListeners()
    }

    // ==================== Public API ====================
    
    fun getPort(): Int? = port
    fun getApiClient(): OpenCodeApiClient? = apiClient
    fun isConnected(): Boolean = isConnected.get()
    fun hasTerminalUI(): Boolean = terminalVirtualFile?.let { 
        FileEditorManager.getInstance(project).openFiles.contains(it) 
    } ?: false

    val sessionManager: SessionManager get() = project.service()
    private val diffViewerService: DiffViewerService get() = project.service()

    /**
     * Main entry point for connecting to OpenCode.
     * 
     * @param interactive If true, user explicitly triggered this (show dialogs).
     *                    If false, background operation (prefer silent recovery).
     */
    fun focusOrCreateTerminal(interactive: Boolean = false) {
        val hasUI = hasTerminalUI()
        val hasConfig = port != null
        val connected = isConnected.get()
        
        logger.info("focusOrCreateTerminal: interactive=$interactive, connected=$connected, hasUI=$hasUI, hasConfig=$hasConfig")

        when {
            // Case 1: Already connected
            connected -> handleConnectedState(hasUI, interactive)
            
            // Case 2: Has UI but disconnected -> Focus and recover
            hasUI -> handleDisconnectedWithUI(interactive)
            
            // Case 3: No UI but has config -> Attempt recovery
            hasConfig -> handleDisconnectedWithConfig(interactive)
            
            // Case 4: Clean slate -> Show dialog
            else -> showConnectionDialog()
        }
    }

    /**
     * Send text to OpenCode prompt (via TUI API or TTY fallback).
     * Will trigger connection recovery if needed.
     */
    fun focusOrCreateTerminalAndPaste(text: String, attempts: Int = 20, delayMs: Long = 100L) {
        if (text.isBlank() || project.isDisposed) return
        
        focusOrCreateTerminal(interactive = false)
        schedulePasteAttempt(text, attempts, delayMs)
    }

    /**
     * Send text to the active OpenCode instance.
     * @return true if accepted for processing, false if no connection available.
     */
    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false
        
        val client = apiClient
        val widget = terminalEditor?.terminalWidget

        // Strategy 1: TUI API (works for both local and remote)
        if (client != null) {
            AppExecutorUtil.getAppExecutorService().submit {
                var success = false
                try {
                    success = client.tuiAppendPrompt(text)
                    if (!success) logger.warn("TUI API returned false")
                } catch (e: Exception) {
                    logger.warn("TUI API error: ${e.message}")
                }
                
                // Fallback to TTY if API failed and we have local terminal
                if (!success && widget != null) {
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            widget.ttyConnector?.write(text.toByteArray(Charsets.UTF_8))
                        } catch (e: Exception) {
                            logger.error("TTY fallback failed", e)
                        }
                    }
                }
            }
            return true
        }

        // Strategy 2: Direct TTY (local-only, legacy)
        if (widget != null) {
            try {
                widget.ttyConnector?.write(text.toByteArray(Charsets.UTF_8))
                return true
            } catch (e: Exception) {
                logger.error("Direct TTY write failed", e)
            }
        }
        
        return false
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
        listener(isConnected.get())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.remove(listener)
    }

    // ==================== Connection State Handlers ====================

    private fun handleConnectedState(hasUI: Boolean, interactive: Boolean) {
        if (hasUI) {
            focusTerminalUI()
        } else {
            // We are connected, but no UI is visible (tab closed).
            // If we have a valid terminal file, re-open it to restore the session.
            if (terminalVirtualFile != null) {
                focusTerminalUI()
            } else if (interactive) {
                // True headless mode (remote connection, no local terminal created ever)
                showHeadlessStatusDialog()
            }
        }
    }

    private fun handleDisconnectedWithUI(interactive: Boolean) {
        focusTerminalUI()
        startConnectionManager()
        
        if (interactive) {
            // Force immediate retry on user action
            AppExecutorUtil.getAppExecutorService().submit { tryConnect() }
        }
    }

    private fun handleDisconnectedWithConfig(interactive: Boolean) {
        if (interactive) {
            // User action with existing config -> Ask what to do
            ApplicationManager.getApplication().invokeLater {
                val result = Messages.showYesNoCancelDialog(
                    project,
                    "Previous connection to $hostname:$port was lost.\n\nReconnect or configure new connection?",
                    "OpenCode Connection",
                    "Reconnect",
                    "New Connection",
                    "Cancel",
                    Messages.getQuestionIcon()
                )
                when (result) {
                    Messages.YES -> {
                        startConnectionManager()
                        AppExecutorUtil.getAppExecutorService().submit { tryConnect() }
                    }
                    Messages.NO -> {
                        disconnectAndReset()
                        showConnectionDialog()
                    }
                }
            }
        } else {
            // Background operation -> Silent reconnect attempt
            startConnectionManager()
            AppExecutorUtil.getAppExecutorService().submit { tryConnect() }
        }
    }

    private fun showHeadlessStatusDialog() {
        ApplicationManager.getApplication().invokeLater {
            val result = Messages.showYesNoDialog(
                project,
                "Connected to OpenCode at $hostname:$port (Headless Mode)\n\nDisconnect?",
                "OpenCode Status",
                "Disconnect",
                "Keep Connected",
                Messages.getInformationIcon()
            )
            if (result == Messages.YES) {
                disconnectAndReset()
            }
        }
    }

    // ==================== Connection Dialog ====================

    private fun showConnectionDialog() {
        AppExecutorUtil.getAppExecutorService().submit {
            // Default to suggesting a new/free port for creating a new instance
            val suggestedPort = PortFinder.findAvailablePort()
            
            ApplicationManager.getApplication().invokeLater {
                val result = OpenCodeConnectDialog.show(project, suggestedPort)
                if (result != null) {
                    val (inputHost, inputPort) = result
                    processConnectionChoice(inputHost, inputPort)
                }
            }
        }
    }

    /**
     * Process user's connection choice from dialog.
     * - If target port is already running OpenCode -> Connect directly (Headless for remote, Local UI for localhost)
     * - If target port is free -> Create new terminal and start server
     */
    private fun processConnectionChoice(host: String, port: Int) {
        val isLocalhost = host in listOf("0.0.0.0", "127.0.0.1", "localhost")
        
        AppExecutorUtil.getAppExecutorService().submit {
            // Check if target port is already running OpenCode
            val auth = if (isLocalhost) ProcessAuthDetector.detectAuthForPort(port) 
                       else ProcessAuthDetector.ServerAuth("opencode", null)
            val isRunning = PortFinder.isOpenCodeRunningOnPort(port, host, auth.username, auth.password)
            
            ApplicationManager.getApplication().invokeLater {
                if (isRunning) {
                    // Port is occupied by OpenCode -> Connect to existing server (Headless)
                    logger.info("Port $port is running OpenCode, connecting to existing server (Headless)")
                    connectToExistingServer(host, port, auth, createUI = false)
                } else if (isLocalhost) {
                    // Localhost + port free -> Create new local instance
                    logger.info("Port $port is free, creating new terminal")
                    createLocalTerminal(host, port)
                } else {
                    // Remote + port not running -> Show error
                    Messages.showErrorDialog(
                        project,
                        "Cannot connect to $host:$port\n\nNo OpenCode server is running on that address.",
                        "Connection Failed"
                    )
                }
            }
        }
    }

    // ==================== Connection Implementations ====================

    /**
     * Connect to an existing OpenCode server.
     * @param createUI If true, create a local terminal that connects to the server (for localhost).
     *                 If false, use headless mode (for remote).
     */
    private fun connectToExistingServer(host: String, port: Int, auth: ProcessAuthDetector.ServerAuth, createUI: Boolean) {
        this.hostname = host
        this.port = port
        this.username = auth.username
        this.password = auth.password
        
        if (createUI) {
            // Create terminal UI that runs `opencode --hostname --port` to connect
            createTerminalUI(host, port)
        }
        
        initializeApiClient(host, port)
        startConnectionManager()
        
        if (!createUI) {
            // Headless mode -> Show confirmation
            Messages.showInfoMessage(project, "Connected to $host:$port", "OpenCode")
        }
    }

    /**
     * Create a new local OpenCode instance with terminal UI.
     */
    private fun createLocalTerminal(host: String, port: Int) {
        this.hostname = host
        this.port = port
        
        // Auth will be detected after server starts
        createTerminalUI(host, port)
        initializeApiClient(host, port)
        startConnectionManager()
    }

    private fun createTerminalUI(host: String, port: Int) {
        val terminalView = TerminalView.getInstance(project)
        val widget = terminalView.createLocalShellWidget(project.basePath, OPEN_CODE_TAB_NAME)
        OpenCodeTerminalLinkFilter.install(project, widget)

        val virtualFile = OpenCodeTerminalVirtualFile(OPEN_CODE_TAB_NAME)
        terminalVirtualFile = virtualFile
        OpenCodeTerminalFileEditorProvider.registerWidget(virtualFile, widget)

        ApplicationManager.getApplication().invokeLater {
            val editors = FileEditorManager.getInstance(project).openFile(virtualFile, true)
            
            // Pin the tab to keep it on the left and prevent accidental closing
            pinTerminalTab(virtualFile)
            
            terminalEditor = editors.firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor
            
            if (terminalEditor != null) {
                // Use --continue to load the most recent session if available
                val command = "opencode --hostname $host --port $port --continue"
                widget.executeCommand(command)
            } else {
                logger.error("Failed to create terminal editor")
            }
        }
    }

    private fun initializeApiClient(host: String, port: Int) {
        // Detect auth if not already set (for new local instances)
        if (username == null && (host == "127.0.0.1" || host == "localhost")) {
            val auth = ProcessAuthDetector.detectAuthForPort(port)
            username = auth.username
            password = auth.password
        }

        // Sanitize host: If 0.0.0.0 is used (e.g. from detected binding), 
        // HTTP client must use 127.0.0.1 to actually connect on localhost.
        val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host

        val client = OpenCodeApiClient(apiHost, port, username, password)
        apiClient = client
        sessionManager.setApiClient(client)
        logger.info("API Client initialized: $apiHost:$port (Original host: $host)")
    }

    private fun focusTerminalUI() {
        terminalVirtualFile?.let { 
            FileEditorManager.getInstance(project).openFile(it, true)
            pinTerminalTab(it)
        }
    }
    
    /**
     * Pins the terminal tab. Pinned tabs are typically displayed on the left side
     * and are protected from automatic closing.
     */
    private fun pinTerminalTab(file: VirtualFile) {
        // Disabled per user request - users prefer normal tabs with close buttons.
        // The stability issue is now resolved via "Delayed Disposal" in the provider.
        /*
        try {
            val managerEx = FileEditorManagerEx.getInstanceEx(project)
            val window = managerEx.currentWindow
            if (window != null && !window.isFilePinned(file)) {
                window.setFilePinned(file, true)
            }
        } catch (e: Exception) {
            logger.debug("Failed to pin terminal tab", e)
        }
        */
    }

    // ==================== Connection Manager ====================

    @Synchronized
    private fun startConnectionManager() {
        if (connectionManagerTask?.isDone == false) return
        if (port == null || apiClient == null) return

        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (!project.isDisposed) tryConnect()
        }, 0, CONNECTION_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect() {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return

        try {
            val client = apiClient ?: return
            val projectPath = project.basePath ?: return
            
            if (client.checkHealth(projectPath)) {
                logger.info("Server healthy, connecting SSE")
                sessionManager.refreshActiveSession()
                connectToSse()
            }
        } catch (e: Exception) {
            logger.debug("Connection attempt failed: ${e.message}")
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
            onEvent = { handleEvent(it) },
            onError = { 
                logger.debug("SSE error: ${it.message}")
                updateConnectionState(false)
            },
            onConnected = {
                logger.info("SSE connected")
                updateConnectionState(true)
                sessionManager.initializeBaseline()
            },
            onDisconnected = {
                logger.info("SSE disconnected")
                updateConnectionState(false)
            }
        )
        sseListener?.connect()
    }

    private fun updateConnectionState(connected: Boolean) {
        if (isConnected.getAndSet(connected) != connected) {
            connectionListeners.forEach { it(connected) }
        }
    }

    // ==================== Event Handling ====================

    private fun handleEvent(event: OpenCodeEvent) {
        when (event) {
            is SessionStatusEvent -> sessionManager.onSessionStatusChanged(event.properties.sessionID, event.properties.status)
            is SessionIdleEvent -> fetchAndShowDiffs(event.properties.sessionID)
            is FileEditedEvent -> sessionManager.onFileEdited(event.properties.file)
            else -> {}
        }
    }

    private fun fetchAndShowDiffs(sessionId: String, attempt: Int = 1) {
        val client = apiClient ?: return
        val projectPath = project.basePath ?: return
        
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                var diffs = client.getSessionDiff(sessionId, projectPath)
                
                // Fallback to session summary if direct diff is empty
                if (diffs.isEmpty()) {
                    val session = client.getSession(sessionId, projectPath)
                    diffs = session?.summary?.diffs ?: emptyList()
                }
                
                if (diffs.isNotEmpty()) {
                    processDiffs(sessionId, diffs)
                } else if (attempt < 3) {
                    Thread.sleep(2000)
                    fetchAndShowDiffs(sessionId, attempt + 1)
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch diffs", e)
            }
        }
    }

    private fun processDiffs(sessionId: String, diffs: List<FileDiff>) {
        sessionManager.clearDiffs()
        val newDiffs = sessionManager.filterNewDiffs(diffs)
        sessionManager.updateProcessedDiffs(diffs)
        
        if (newDiffs.isNotEmpty()) {
            val entries = newDiffs.map { DiffEntry(sessionId, null, null, it, System.currentTimeMillis()) }
            sessionManager.onDiffReceived(DiffBatch(sessionId, null, newDiffs))
            sessionManager.onSessionCompleted(sessionId, newDiffs.size)
            
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) diffViewerService.showMultiFileDiff(entries)
            }
        }
    }

    // ==================== Paste Retry Logic ====================

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

    // ==================== Cleanup ====================

    private fun setupEditorListeners() {
        // Removed fileClosed listener that caused premature disconnection.
        // We now allow the terminal session to persist in the background even if the tab is closed.
        // Cleanup happens in dispose() (Project close).
    }

    override fun dispose() {
        disconnectAndReset()
        OpenCodeTerminalFileEditorProvider.clearAll()
    }

    private fun disconnectAndReset() {
        connectionManagerTask?.cancel(true)
        connectionManagerTask = null
        sseListener?.disconnect()
        sseListener = null
        connectionListeners.clear()
        isConnected.set(false)
        isConnecting.set(false)
        
        terminateProcess()
        
        terminalVirtualFile?.let { OpenCodeTerminalFileEditorProvider.unregisterWidget(it) }
        terminalVirtualFile = null
        terminalEditor = null
        
        port = null
        hostname = "127.0.0.1"
        username = null
        password = null
        apiClient = null
    }

    private fun terminateProcess() {
        try {
            val process = terminalEditor?.terminalWidget?.processTtyConnector?.process
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to terminate process: ${e.message}")
        }
    }
}

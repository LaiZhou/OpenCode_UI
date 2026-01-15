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
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
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
        private const val OPEN_CODE_TAB_NAME = "OpenCode"
        private const val CONNECTION_RETRY_INTERVAL_MS = 5000L
    }

    private var hostname: String = "0.0.0.0"
    private var port: Int? = null
    private var username: String? = null
    private var password: String? = null
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null
    
    // Dev mode flag
    private var isDevMode: Boolean = false
    
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null
    private var terminalVirtualFile: OpenCodeTerminalVirtualFile? = null
    private var terminalEditor: OpenCodeTerminalFileEditor? = null
    
    init {
        // Check environment variable for development mode
        val envDevMode = System.getenv("OPENCODE_DEV_MODE")
        if (envDevMode == "true") {
            isDevMode = true
            logger.info("OpenCode running in DEV MODE (from env var)")
        }
        
        setupEditorListeners()
    }

    fun getPort(): Int? = port
    fun getApiClient(): OpenCodeApiClient? = apiClient
    fun isConnected(): Boolean = isConnected.get()

    val sessionManager: SessionManager
        get() = project.service()

    private val diffViewerService: DiffViewerService
        get() = project.service()

    private fun setupEditorListeners() {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file is OpenCodeTerminalVirtualFile && file == terminalVirtualFile) {
                    logger.info("OpenCode terminal closed, resetting connection state")
                    port = null
                    hostname = "0.0.0.0"
                    username = null
                    password = null
                    connectionManagerTask?.cancel(true)
                    sseListener?.disconnect()
                    apiClient = null
                    isConnected.set(false)
                    
                    OpenCodeTerminalFileEditorProvider.unregisterWidget(file)
                    terminalVirtualFile = null
                    terminalEditor = null
                }
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                val newFile = event.newFile
                if (newFile is OpenCodeTerminalVirtualFile && newFile == terminalVirtualFile) {
                    // When user switches back to OpenCode terminal tab, ensure widget gets focus
                    // This handles cases like returning from Diff View or other editors
                    ApplicationManager.getApplication().invokeLater {
                        terminalEditor?.terminalWidget?.preferredFocusableComponent?.requestFocusInWindow()
                    }
                }
            }
        })
    }

    fun hasOpenCodeTerminal(): Boolean {
        val fileEditorManager = FileEditorManager.getInstance(project)
        return fileEditorManager.openFiles.any { it is OpenCodeTerminalVirtualFile }
    }

    fun focusOrCreateTerminal() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val existingFile = terminalVirtualFile

        if (existingFile != null && fileEditorManager.openFiles.contains(existingFile)) {
            fileEditorManager.openFile(existingFile, true)
            
            if (port == null) {
                AppExecutorUtil.getAppExecutorService().submit {
                    try {
                        val runningPort = PortFinder.findRunningOpenCodeServer()
                        if (runningPort != null) {
                            port = runningPort
                            logger.info("Found running OpenCode server on port: $runningPort")
                            
                            val auth = ProcessAuthDetector.detectAuthForPort(runningPort)
                            username = auth.username
                            password = auth.password
                            
                            ApplicationManager.getApplication().invokeLater {
                                initializeApiClient(runningPort)
                                startConnectionManager()
                            }
                        } else {
                            logger.warn("Existing OpenCode terminal found but no running server detected")
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to detect running OpenCode server", e)
                    }
                }
            } else {
                startConnectionManager()
            }
        } else {
            showConnectDialogAndCreateTerminal()
        }
    }

    private fun showConnectDialogAndCreateTerminal() {
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val defaultPort = PortFinder.findAvailablePort()
                logger.info("Default available port: $defaultPort")

                ApplicationManager.getApplication().invokeLater {
                    val result = OpenCodeConnectDialog.show(project, defaultPort)
                    if (result != null) {
                        val (userHostname, userPort) = result
                        hostname = userHostname
                        port = userPort
                        
                        val isLocalhost = userHostname in listOf("0.0.0.0", "127.0.0.1", "localhost")
                        val isDefaultPort = userPort == defaultPort
                        
                        if (isLocalhost && isDefaultPort) {
                            logger.info("Using default local address, creating terminal: $hostname:$port")
                            createTerminalAndConnect(userHostname, userPort)
                        } else {
                            logger.info("Custom address specified, connecting to existing server: $hostname:$port")
                            connectToExistingServer(userPort)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to find available port", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to find available port: ${e.message}",
                        "OpenCode Connection Error"
                    )
                }
            }
        }
    }

    private fun connectToExistingServer(port: Int) {
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val projectPath = project.basePath ?: return@submit
                
                val auth = ProcessAuthDetector.detectAuthForPort(port)
                username = auth.username
                password = auth.password
                
                if (PortFinder.isOpenCodeRunningOnPort(port, hostname, username, password)) {
                    logger.info("OpenCode server found on $hostname:$port, starting connection manager")
                    ApplicationManager.getApplication().invokeLater {
                        initializeApiClient(port)
                        startConnectionManager()
                        
                        // Show success notification
                        Messages.showInfoMessage(
                            project,
                            "Successfully connected to OpenCode server at $hostname:$port",
                            "OpenCode Connection Success"
                        )
                    }
                } else {
                    logger.warn("No OpenCode server running on $hostname:$port")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Cannot connect to OpenCode server on $hostname:$port. Please ensure the server is running.",
                            "OpenCode Connection Error"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to connect to existing server", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to connect to server: ${e.message}",
                        "OpenCode Connection Error"
                    )
                }
            }
        }
    }

    private fun createTerminalAndConnect(hostname: String, port: Int) {
        val terminalView = TerminalView.getInstance(project)
        val widget = terminalView.createLocalShellWidget(project.basePath, OPEN_CODE_TAB_NAME)
        OpenCodeTerminalLinkFilter.install(project, widget)

        val virtualFile = OpenCodeTerminalVirtualFile(OPEN_CODE_TAB_NAME)
        
        terminalVirtualFile = virtualFile
        
        // Register widget before opening file
        OpenCodeTerminalFileEditorProvider.registerWidget(virtualFile, widget)

        // Defer opening file to the next EDT cycle to avoid potential deadlocks
        // with FileEditorManager's synchronous wait logic
        ApplicationManager.getApplication().invokeLater {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editors = fileEditorManager.openFile(virtualFile, true)
            
            // Improved error handling
            terminalEditor = editors.firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor
            
            if (terminalEditor == null) {
                logger.error("Failed to create OpenCode terminal editor")
                OpenCodeTerminalFileEditorProvider.unregisterWidget(virtualFile)
                terminalVirtualFile = null
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to create OpenCode terminal. Please try again.",
                        "OpenCode Terminal Error"
                    )
                }
                return@invokeLater
            }
            
            val command = "opencode --hostname $hostname --port $port"
            widget.executeCommand(command)

            initializeApiClient(port)
            startConnectionManager()
        }
    }

    private fun initializeApiClient(port: Int) {
        this.port = port
        val client = OpenCodeApiClient(hostname, port, username, password)
        apiClient = client
        sessionManager.setApiClient(client)
        if (password != null) {
            logger.info("API client initialized on $hostname:$port with authentication")
        } else {
            logger.info("API client initialized on $hostname:$port")
        }
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
                
                // Initialize diff baseline to prevent pre-existing untracked files from showing up in the first round
                sessionManager.initializeBaseline()
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
                // Just log; actual diff processing happens on session.idle
                // This avoids duplicate/unfiltered diffs being added
                val diffBatch = event.properties.toDiffBatch()
                logger.debug("SSE: ${diffBatch.diffs.size} diffs reported (will process on idle)")
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
        logger.info("[Diff Process] Starting: session=$sessionId, server returned ${diffs.size} diffs")
        
        // Clear old diffs to ensure we only show the current round of changes
        sessionManager.clearDiffs()
        
        // Filter out diffs that haven't changed (Implicit Accept)
        val newDiffs = sessionManager.filterNewDiffs(diffs)
        sessionManager.updateProcessedDiffs(diffs) // Always update state with latest
        
        if (newDiffs.isEmpty()) {
            logger.info("[Diff Process] No new diffs to show (all filtered as duplicates/implicit accepts)")
            return
        }
        
        logger.info("[Diff Process] Showing ${newDiffs.size} new diffs: ${newDiffs.map { it.file }}")
        
        val entries = newDiffs.map { DiffEntry(sessionId, null, null, it, System.currentTimeMillis()) }
        sessionManager.onDiffReceived(DiffBatch(sessionId, null, newDiffs))
        
        // Create LocalHistory label for this session
        sessionManager.onSessionCompleted(sessionId, newDiffs.size)

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
        
        val editor = terminalEditor ?: return false
        val virtualFile = terminalVirtualFile ?: return false
        val widget = editor.terminalWidget ?: return false

        widget.ttyConnector?.let { tty ->
            // Use byte array write for atomic operation
            // UTF-8 encoding ensures proper handling of CJK characters
            tty.write(text.toByteArray(Charsets.UTF_8))
        }
        
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(virtualFile, true)
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
        logger.debug("Disposing OpenCodeService")
        connectionManagerTask?.cancel(true)
        sseListener?.disconnect()
        connectionListeners.clear()
        isConnected.set(false)
        
        // Terminate the OpenCode process to release the port
        terminateOpenCodeProcess()
        
        terminalVirtualFile?.let { OpenCodeTerminalFileEditorProvider.unregisterWidget(it) }
        terminalVirtualFile = null
        terminalEditor = null
        
        // Clear static map for dynamic plugin unloading support
        OpenCodeTerminalFileEditorProvider.clearAll()
    }

    /**
     * Terminate the OpenCode process running in the terminal.
     * This releases the port when IDE closes.
     */
    private fun terminateOpenCodeProcess() {
        try {
            val widget = terminalEditor?.terminalWidget ?: return
            val processTtyConnector = widget.processTtyConnector ?: return
            val process = processTtyConnector.process
            
            if (process.isAlive) {
                logger.info("Terminating OpenCode process to release port $port")
                process.destroy()
                
                // Give it a moment to terminate gracefully
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    logger.warn("OpenCode process did not terminate gracefully, forcing...")
                    process.destroyForcibly()
                }
                logger.info("OpenCode process terminated")
            }
        } catch (e: Exception) {
            logger.warn("Failed to terminate OpenCode process: ${e.message}")
        }
    }
}

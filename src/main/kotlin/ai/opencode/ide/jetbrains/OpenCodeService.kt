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
import ai.opencode.ide.jetbrains.web.OpenCodeWebVirtualFile
import ai.opencode.ide.jetbrains.web.WebModeSupport
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
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
import ai.opencode.ide.jetbrains.util.PathUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    private enum class ConnectionMode {
        NONE,
        TERMINAL,
        WEB,
        REMOTE
    }

    companion object {
        private const val OPEN_CODE_TAB_PREFIX = "OpenCode"
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
    private var lastMode: ConnectionMode = ConnectionMode.NONE
    private var remoteReconnectFailures = 0
    private var remoteReconnectDialogShown = false
    private var wasEverConnected = false
    
    // UI State (null in Headless mode)
    private var terminalVirtualFile: OpenCodeTerminalVirtualFile? = null
    private var terminalEditor: OpenCodeTerminalFileEditor? = null
    
    // Web Mode State
    private var webVirtualFile: OpenCodeWebVirtualFile? = null

    // Background Tasks & Listeners
    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null
    private val diffFetchTriggerTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
    }

    // ==================== Public API ====================
    
    fun getPort(): Int? = port
    fun getApiClient(): OpenCodeApiClient? = apiClient
    fun isConnected(): Boolean = isConnected.get()
    fun hasTerminalUI(): Boolean {
        val tf = terminalVirtualFile
        if (tf != null && FileEditorManager.getInstance(project).openFiles.contains(tf)) return true
        
        val wf = webVirtualFile
        if (wf != null && FileEditorManager.getInstance(project).openFiles.contains(wf)) return true
        
        return false
    }

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
        
        // Check actual process/server health (not just internal boolean)
        val isServerRunning = port?.let { 
             PortFinder.isOpenCodeRunningOnPort(it, hostname, username, password) 
        } ?: false

        val connected = isConnected.get() && isServerRunning
        
        // If we thought we were connected but server is dead, update state
        if (isConnected.get() && !isServerRunning) {
             logger.info("[Connection] Detected zombie state: isConnected=true but server is dead at $hostname:$port. Resetting state.")
             isConnected.set(false)
        }
        
        logger.info("[Connection] focusOrCreateTerminal: interactive=$interactive, connected=$connected, hasUI=$hasUI, hasConfig=$hasConfig, serverRunning=$isServerRunning (host=$hostname:$port)")

        when {
            // Case 1: Healthy Connection -> Focus UI
            connected && hasUI -> {
                 logger.info("[Connection] Case 1: Healthy connection and UI present. Focusing.")
                 if (interactive) focusTerminalUI()
            }

            // Case 2: Interactive recovery (Tab closed OR Server dead)
            interactive && hasConfig -> {
                 logger.info("[Connection] Case 2: Interactive recovery triggered. serverRunning=$isServerRunning")
                 val message = if (isServerRunning) {
                     "The OpenCode tab is closed, but the session at $hostname:$port is still active.\n\nRestore previous session?"
                 } else {
                     "The OpenCode server at $hostname:$port is not running.\n\nRestart server (preserve history)?"
                 }
                 showReconnectOrNewDialog(message, isServerRunning)
            }

            // Case 3: Background auto-restore (only if server running)
            !interactive && connected && !hasUI -> {
                logger.info("[Connection] Case 3a: Background auto-restore UI (connected, no UI)")
                restoreUiForMode()
            }
            !interactive && !connected && isServerRunning && hasConfig -> {
                logger.info("[Connection] Case 3b: Background auto-restore UI (not connected, server running)")
                restoreUiForMode()
            }

            // Case 4: Background failure (Server dead) -> Handled by handleDisconnectedProcess/ConnectionManager
            !interactive && !isServerRunning && hasConfig -> {
                logger.info("[Connection] Case 4: Background failure (server dead). Delegating to handleDisconnectedProcess.")
                handleDisconnectedProcess(false)
            }

            // Case 5: Clean slate -> Show dialog
            else -> {
                logger.info("[Connection] Case 5: Defaulting to connection dialog. hasConfig=$hasConfig, interactive=$interactive")
                showConnectionDialog()
            }
        }
    }
    
    private fun showReconnectOrNewDialog(message: String, isServerRunning: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val fullMessage = "[$timeStr] $message"
            
            val result = Messages.showYesNoCancelDialog(
                project,
                fullMessage,
                "OpenCode Connection",
                if (isServerRunning) "Restore Session" else "Restart Server",
                "New Connection",
                "Cancel",
                if (isServerRunning) Messages.getQuestionIcon() else Messages.getWarningIcon()
            )
            
            when (result) {
                Messages.YES -> {
                    if (isServerRunning) {
                        restoreUiForMode()
                    } else {
                        restartServer(lastMode)
                    }
                }
                Messages.NO -> {
                    disconnectAndReset()
                    showConnectionDialog()
                }
            }
        }
    }

    /**
     * Send text to OpenCode prompt (via TUI API or TTY fallback).
     * Will trigger connection recovery if needed.
     */
    fun focusOrCreateTerminalAndPaste(text: String, attempts: Int = 20, delayMs: Long = 100L) {
        if (text.isBlank() || project.isDisposed) return

        val client = apiClient
        val widget = terminalEditor?.terminalWidget
        
        // We can proceed if we have ANY means to send the text:
        // 1. API client exists (can try TUI API)
        // 2. Terminal widget exists (can try direct TTY)
        // Only show error if we have no connection mechanism at all
        val canAttemptPaste = client != null || widget != null
        
        if (!canAttemptPaste) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showInfoMessage(
                    project,
                    "OpenCode is not running. Please start or connect to OpenCode first.",
                    "OpenCode"
                )
            }
            return
        }

        schedulePasteAttempt(text, attempts, delayMs)
        
        // Focus terminal tab to let user observe the effect
        ApplicationManager.getApplication().invokeLater {
            focusTerminalUI()
        }
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

    private fun handleDisconnectedProcess(interactive: Boolean) {
        when (lastMode) {
            ConnectionMode.TERMINAL -> {
                if (!interactive) return
                ApplicationManager.getApplication().invokeLater {
                    val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val result = Messages.showYesNoCancelDialog(
                        project,
                        "[$timeStr] The OpenCode server at $hostname:$port is not running.\n\n" +
                            "Do you want to restart the server on the same port (preserving history) or configure a new connection?",
                        "Connection Lost",
                        "Restart Server",
                        "New Connection",
                        "Cancel",
                        Messages.getWarningIcon()
                    )
                    when (result) {
                        Messages.YES -> restartServer(ConnectionMode.TERMINAL)
                        Messages.NO -> {
                            disconnectAndReset()
                            showConnectionDialog()
                        }
                    }
                }
            }
            ConnectionMode.WEB -> {
                if (!interactive) return
                ApplicationManager.getApplication().invokeLater {
                    val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val result = Messages.showYesNoCancelDialog(
                        project,
                        "[$timeStr] The OpenCode server at $hostname:$port is not running.\n\n" +
                            "Do you want to restart the server on the same port (preserving history) or configure a new connection?",
                        "Connection Lost",
                        "Restart Server",
                        "New Connection",
                        "Cancel",
                        Messages.getWarningIcon()
                    )
                    when (result) {
                        Messages.YES -> restartServer(ConnectionMode.WEB)
                        Messages.NO -> {
                            disconnectAndReset()
                            showConnectionDialog()
                        }
                    }
                }
            }
            ConnectionMode.REMOTE -> {
                if (interactive) {
                    showRemoteReconnectDialog()
                }
            }
            ConnectionMode.NONE -> {
                if (interactive) showConnectionDialog()
            }
        }
    }

    private fun restartServer(mode: ConnectionMode) {
        val host = hostname
        val p = port ?: return
        val pwd = password

        logger.info("[Process] Restarting server in mode $mode at $host:$p")
        disconnectAndReset()

        this.hostname = host
        this.port = p
        this.password = pwd
        this.lastMode = mode

        when (mode) {
            ConnectionMode.WEB -> createWebTerminal(host, p, pwd)
            ConnectionMode.TERMINAL -> createLocalTerminal(host, p, pwd)
            else -> {
                logger.warn("[Process] Unknown mode for restart: $mode. Showing dialog.")
                showConnectionDialog()
            }
        }
    }

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
                val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val result = Messages.showYesNoCancelDialog(
                    project,
                    "[$timeStr] Previous connection to $hostname:$port was lost.\n\nReconnect or configure new connection?",
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
            val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val result = Messages.showYesNoDialog(
                project,
                "[$timeStr] Connected to OpenCode at $hostname:$port (Headless Mode)\n\nDisconnect?",
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
                    processConnectionChoice(result.hostname, result.port, result.password, result.useWebInterface)
                }
            }
        }
    }

    /**
     * Process user's connection choice from dialog.
     * - If target port is already running OpenCode -> Connect directly (Headless for remote, Local UI for localhost)
     * - If target port is free -> Create new terminal and start server
     */
    private fun processConnectionChoice(host: String, port: Int, password: String?, useWebInterface: Boolean = false) {
        val isLocalhost = host in listOf("0.0.0.0", "127.0.0.1", "localhost")
        
        AppExecutorUtil.getAppExecutorService().submit {
            // Logic for authentication:
            // 1. If user provided a password, always use it.
            // 2. If no password provided and it's localhost, try to detect it from running processes.
            // 3. Otherwise (remote or no detection), use default username "opencode" and no password.
            
            val auth = when {
                !password.isNullOrBlank() -> {
                    ProcessAuthDetector.ServerAuth("opencode", password)
                }
                isLocalhost -> {
                    ProcessAuthDetector.detectAuthForPort(port)
                }
                else -> {
                    ProcessAuthDetector.ServerAuth("opencode", null)
                }
            }
            
            // For remote hosts (not localhost), skip running check and connect directly.
            // For localhost, check if running to decide whether to connect or start new instance.
            val isRunning = if (isLocalhost) {
                PortFinder.isOpenCodeRunningOnPort(port, host, auth.username, auth.password)
            } else {
                true // Assume remote is running or let connection handle failure
            }
            
            ApplicationManager.getApplication().invokeLater {
                if (isRunning) {
                    if (isLocalhost) {
                        logger.info("Port $port is running OpenCode, connecting to existing local server")
                        lastMode = if (useWebInterface) ConnectionMode.WEB else ConnectionMode.TERMINAL
                    } else {
                        logger.info("Connecting to remote server $host:$port")
                        lastMode = ConnectionMode.REMOTE
                    }
                    connectToExistingServer(host, port, auth, createUI = useWebInterface || !isLocalhost, useWebInterface = useWebInterface)
                } else {
                    // This block only reached for localhost when not running
                    logger.info("Port $port is free, creating new local instance (Web=$useWebInterface)")
                    lastMode = if (useWebInterface) ConnectionMode.WEB else ConnectionMode.TERMINAL
                    if (useWebInterface) {
                        createWebTerminal(host, port, auth.password)
                    } else {
                        createLocalTerminal(host, port, auth.password)
                    }
                }
            }
        }
    }

    // ==================== Connection Implementations ====================

    /**
     * Connect to an existing OpenCode server.
     * @param createUI If true, create a local UI (Terminal or Web).
     * @param useWebInterface If true and createUI is true, create Web UI.
     */
    private fun connectToExistingServer(
        host: String, 
        port: Int, 
        auth: ProcessAuthDetector.ServerAuth, 
        createUI: Boolean, 
        useWebInterface: Boolean = false
    ) {
        this.hostname = host
        this.port = port
        this.username = auth.username
        this.password = auth.password

        val isLocalhost = host in listOf("0.0.0.0", "127.0.0.1", "localhost")
        lastMode = if (!isLocalhost) ConnectionMode.REMOTE else if (useWebInterface) ConnectionMode.WEB else ConnectionMode.TERMINAL
        
        if (createUI) {
            if (useWebInterface) {
                createWebUI(host, port)
            } else {
                createTerminalUI(host, port, auth.password, continueSession = false)
            }
        }
        
        initializeApiClient(host, port)
        startConnectionManager()
        
        if (!createUI) {
            Messages.showInfoMessage(project, "Connected to $host:$port", "OpenCode")
        }
    }

    /**
     * Create a new local OpenCode instance with Web UI.
     */
    private fun createWebTerminal(host: String, port: Int, password: String?) {
        if (!ensureOpenCodeCliAvailable()) {
            return
        }

        this.hostname = host
        this.port = port
        this.username = if (password.isNullOrBlank()) null else "opencode"
        this.password = password?.takeIf { it.isNotBlank() }
        this.lastMode = ConnectionMode.WEB

        try {
            logger.info("[WebMode] Starting Web Mode process for $host:$port")
            
            // Launch TUI terminal as editor tab (same as Terminal mode)
            createTerminalUI(host, port, password, continueSession = true)

            // Move blocking wait to background
            AppExecutorUtil.getAppExecutorService().submit {
                logger.info("[WebMode] Waiting for port $host:$port to become ready...")
                val startTime = System.currentTimeMillis()
                // Increased timeout to 30s to accommodate slow startup or heavy load
                if (PortFinder.waitForPort(port, host, timeoutMs = 30000, requireHealth = true)) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.info("[WebMode] Port $host:$port is ready (took ${duration}ms). Creating Web UI.")
                    ApplicationManager.getApplication().invokeLater {
                         createWebUI(host, port)
                    }
                    initializeApiClient(host, port)
                    startConnectionManager()
                } else {
                    logger.warn("[WebMode] Port $host:$port timed out after 30s. Terminating process.")
                    terminateProcess()
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, buildStartupFailureMessage(port), "Error")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("[WebMode] Failed to start web process: ${e.message}", e)
            Messages.showErrorDialog(project, "Failed to start OpenCode server: ${e.message}", "Error")
        }
    }

    private fun createWebUI(host: String, port: Int) {
        val virtualFile = WebModeSupport.openWebTab(project, host, port, password) {
            // DO NOT terminate process here.
            // This allows the user to re-open the tab later and resume the session.
            // The process will be terminated when:
            // 1. Project closes (dispose)
            // 2. User chooses "New Connection"
            // 3. User manually stops it
        }
        
        if (virtualFile == null) {
            terminateProcess()
            return
        }
        
        webVirtualFile = virtualFile
    }

    /**
     * Create a new local OpenCode instance with terminal UI.
     */
    private fun createLocalTerminal(host: String, port: Int, password: String?) {
        if (!ensureOpenCodeCliAvailable()) {
            return
        }

        this.hostname = host
        this.port = port
        this.username = if (password.isNullOrBlank()) null else "opencode"
        this.password = password?.takeIf { it.isNotBlank() }
        this.lastMode = ConnectionMode.TERMINAL

        // Start checking for port availability in background to initialize API client when ready
        // Note: For terminal, we don't block UI creation because the terminal itself 
        // will show the shell process output. But we do need to wait for the API.
        AppExecutorUtil.getAppExecutorService().submit {
            if (PortFinder.waitForPort(port, host)) {
                initializeApiClient(host, port)
                startConnectionManager()
            }
        }
        
        createTerminalUI(host, port, password)
    }

    private fun createTerminalUI(host: String, port: Int, password: String?, continueSession: Boolean = true) {
        if (!ensureOpenCodeCliAvailable()) {
            return
        }

        val terminalView = TerminalView.getInstance(project)
        val tabName = "$OPEN_CODE_TAB_PREFIX($port)"
        val widget = terminalView.createLocalShellWidget(project.basePath, tabName)
        OpenCodeTerminalLinkFilter.install(project, widget)

        val virtualFile = OpenCodeTerminalVirtualFile(tabName)
        terminalVirtualFile = virtualFile
        OpenCodeTerminalFileEditorProvider.registerWidget(virtualFile, widget)

        ApplicationManager.getApplication().invokeLater {
            val editors = FileEditorManager.getInstance(project).openFile(virtualFile, true)
            
            terminalEditor = editors.firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor
            
            if (terminalEditor != null) {
                // Use --continue to load the most recent session if available
                val command = buildOpenCodeCommand(host, port, password, continueSession)
                widget.executeCommand(command)
                
                // Pin the tab to keep it on the left (IntelliJ default behavior for pinned tabs)
                pinTerminalTab(virtualFile)
            } else {
                logger.error("Failed to create terminal editor")
            }
        }
    }
    
    /**
     * Build the opencode command with optional password via environment variable.
     */
    private fun buildOpenCodeCommand(host: String, port: Int, password: String?, continueSession: Boolean): String {
        val continueFlag = if (continueSession) " --continue" else ""
        val baseCommand = "opencode --hostname $host --port $port$continueFlag"
        if (password.isNullOrBlank()) {
            return baseCommand
        }

        return if (isWindows()) {
            val escaped = escapeForWindowsCmd(password)
            // Wrap in quotes to protect the && operator from PowerShell parser
            // PowerShell: cmd /c "set \"VAR=VAL\" && opencode" -> Works
            // CMD: cmd /c "set \"VAR=VAL\" && opencode" -> Works
            "cmd /c \"set \"OPENCODE_SERVER_PASSWORD=$escaped\" && $baseCommand\""
        } else {
            val escaped = escapeForPosix(password)
            "OPENCODE_SERVER_PASSWORD='$escaped' $baseCommand"
        }
    }

    private fun escapeForWindowsCmd(value: String): String {
        return value.replace("\"", "\\\"")
    }

    private fun escapeForPosix(value: String): String {
        return value.replace("'", "'\\''")
    }

    private fun isWindows(): Boolean {
        val osName = System.getProperty("os.name", "").lowercase()
        return osName.contains("windows")
    }

    private fun isLinux(): Boolean {
        val osName = System.getProperty("os.name", "").lowercase()
        return osName.contains("linux")
    }

    private fun ensureOpenCodeCliAvailable(): Boolean {
        if (!isWindows() && !isLinux()) {
            return true
        }

        val commands = if (isWindows()) {
            listOf(listOf("cmd", "/c", "where", "opencode"))
        } else {
            listOf(listOf("which", "opencode"), listOf("sh", "-lc", "command -v opencode"))
        }

        val available = commands.any { command ->
            try {
                val handler = CapturingProcessHandler(GeneralCommandLine(command))
                val output = handler.runProcess(1500)
                output.exitCode == 0 && output.stdout.trim().isNotEmpty()
            } catch (e: Exception) {
                logger.debug("OpenCode CLI lookup failed for $command: ${e.message}")
                false
            }
        }

        if (!available) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(
                    project,
                    "OpenCode CLI was not found in PATH. Install the OpenCode Terminal CLI " +
                        "(not Desktop) and restart the IDE. Example: npm i -g opencode-ai",
                    "OpenCode CLI Not Found"
                )
            }
        }

        return available
    }

    private fun buildStartupFailureMessage(port: Int): String {
        val baseMessage = "Server failed to start on port $port. Check logs for details."
        if (!isWindows() && !isLinux()) {
            return baseMessage
        }

        return buildString {
            append(baseMessage)
            append("\n\nCommon fixes:\n")
            append("- Ensure the OpenCode CLI is installed and on PATH.\n")
            append("- If the server runs in WSL or a container, use its IP instead of 127.0.0.1.")
        }
    }
    
    /**
     * Pins the terminal tab. 
     * Pinned tabs are displayed on the left side of the editor tab bar (before unpinned tabs).
     */
    private fun pinTerminalTab(file: VirtualFile) {
        try {
            val managerEx = FileEditorManagerEx.getInstanceEx(project)
            
            // Try to pin in the current window
            managerEx.currentWindow?.let { window ->
                if (!window.isFilePinned(file)) {
                    window.setFilePinned(file, true)
                }
                return
            }
        } catch (e: Exception) {
            logger.debug("Failed to pin terminal tab", e)
        }
    }

    private fun initializeApiClient(host: String, port: Int) {
        // Sanitize host: If 0.0.0.0 is used (e.g. from detected binding), 
        // HTTP client must use 127.0.0.1 to actually connect on localhost.
        val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host

        val client = OpenCodeApiClient(apiHost, port, username, password)
        apiClient = client
        sessionManager.setApiClient(client)
        logger.info("API Client initialized: $apiHost:$port (Original host: $host)")
    }

    private fun focusTerminalUI() {
        terminalVirtualFile?.let { tf ->
            FileEditorManager.getInstance(project).openFile(tf, true)
            return
        }
        webVirtualFile?.let { wf ->
            FileEditorManager.getInstance(project).openFile(wf, true)
        }
    }

    private fun restoreUiForMode() {
        when (lastMode) {
            ConnectionMode.TERMINAL -> ensureTerminalUi()
            ConnectionMode.WEB -> ensureWebUi()
            ConnectionMode.REMOTE -> showHeadlessStatusDialog()
            ConnectionMode.NONE -> showConnectionDialog()
        }
    }

    private fun ensureTerminalUi() {
        val host = hostname
        val p = port ?: return
        val pwd = password
        val file = terminalVirtualFile

        if (file != null && OpenCodeTerminalFileEditorProvider.hasWidget(file)) {
            focusTerminalUI()
            return
        }

        createTerminalUI(host, p, pwd, continueSession = false)
    }

    private fun ensureWebUi() {
        val host = hostname
        val p = port ?: return

        if (webVirtualFile != null) {
            focusTerminalUI()
            return
        }

        createWebUI(host, p)
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
            
            logger.debug("[Connection] Checking health for $hostname:$port...")
            if (client.checkHealth(projectPath)) {
                logger.info("[Connection] Server $hostname:$port is healthy. Initializing SSE connection.")
                remoteReconnectFailures = 0 // Reset failure count on success
                remoteReconnectDialogShown = false
                wasEverConnected = true
                sessionManager.refreshActiveSession()
                connectToSse()
            } else {
                  logger.warn("[Connection] Health check failed for $hostname:$port (Server returned non-200 or connection refused)")
                  handleConnectionFailure()
            }
        } catch (e: Exception) {
            logger.debug("[Connection] Health check exception for $hostname:$port: ${e.message}")
            handleConnectionFailure()
        } finally {
            isConnecting.set(false)
        }
    }

    private fun handleConnectionFailure() {
        if (lastMode != ConnectionMode.REMOTE && lastMode != ConnectionMode.WEB && lastMode != ConnectionMode.TERMINAL) return
        if (!wasEverConnected) return

        remoteReconnectFailures++
        logger.info("[Connection] failureCount=$remoteReconnectFailures for $hostname:$port")
        // Retry logic: If failed once (approx 5s), silently retry.
        // If failed twice (approx 10s) and haven't shown dialog, show it.
        if (remoteReconnectFailures >= 2 && !remoteReconnectDialogShown) {
            logger.warn("[Connection] Connection lost multiple times. Showing reconnect dialog.")
            remoteReconnectDialogShown = true
            showRemoteReconnectDialog()
        }
    }

    private fun showRemoteReconnectDialog() {
        ApplicationManager.getApplication().invokeLater {
            if (isConnected.get()) return@invokeLater // Recovered while waiting for UI thread
            
            val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val result = Messages.showYesNoCancelDialog(
                project,
                "[$timeStr] Connection to remote server $hostname:$port lost.\n\nReconnect?",
                "Connection Lost",
                "Reconnect",
                "New Connection",
                "Cancel",
                Messages.getWarningIcon()
            )
            
            when (result) {
                Messages.YES -> {
                    remoteReconnectFailures = 0
                    remoteReconnectDialogShown = false
                    // Will naturally retry on next timer tick
                }
                Messages.NO -> {
                    disconnectAndReset()
                    showConnectionDialog()
                }
                Messages.CANCEL -> {
                    // Do nothing, but stop pestering? Or disconnect?
                    // Typically 'Cancel' implies 'Stop trying' in this context
                    disconnectAndReset()
                }
            }
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
            is SessionStatusEvent -> {
                sessionManager.onSessionStatusChanged(event.properties.sessionID, event.properties.status)
                // Trigger diff fetch when session becomes idle (legacy support or reliable fallback)
                if (event.properties.status.isIdle()) {
                    triggerDiffFetch(event.properties.sessionID)
                }
            }
            is SessionIdleEvent -> triggerDiffFetch(event.properties.sessionID)
            is FileEditedEvent -> sessionManager.onFileEdited(event.properties.file)
            else -> {}
        }
    }

    private fun triggerDiffFetch(sessionId: String) {
        val now = System.currentTimeMillis()
        val last = diffFetchTriggerTimes[sessionId]
        if (last != null && now - last < 1500) {
            logger.info("Skip duplicate diff fetch trigger for session $sessionId")
            return
        }
        diffFetchTriggerTimes[sessionId] = now
        fetchAndShowDiffs(sessionId)
    }

    private fun fetchAndShowDiffs(sessionId: String, attempt: Int = 1) {
        val client = apiClient ?: return
        val projectPath = project.basePath ?: return
        
        logger.info("Fetching diffs for session $sessionId (attempt $attempt)")
        
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                var diffs = client.getSessionDiff(sessionId, projectPath)
                logger.info("Fetched ${diffs.size} diffs from API")
                
                // Fallback to session summary if direct diff is empty
                if (diffs.isEmpty()) {
                    val session = client.getSession(sessionId, projectPath)
                    diffs = session?.summary?.diffs ?: emptyList()
                    logger.info("Fetched ${diffs.size} diffs from Summary fallback")
                }
                
                if (diffs.isNotEmpty()) {
                    processDiffs(sessionId, diffs)
                } else if (attempt < 3) {
                    logger.info("Diffs empty, retrying in 2s...")
                    Thread.sleep(2000)
                    fetchAndShowDiffs(sessionId, attempt + 1)
                } else {
                    logger.info("Diffs empty after retries, giving up.")
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch diffs", e)
            }
        }
    }

    private fun processDiffs(sessionId: String, diffs: List<FileDiff>) {
        sessionManager.clearDiffs()
        val newDiffs = sessionManager.filterNewDiffs(diffs)

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
        
        webVirtualFile?.let { WebModeSupport.unregisterCallback(it) }
        webVirtualFile = null
        
        port = null
        hostname = "127.0.0.1"
        username = null
        password = null
        apiClient = null
    }

    private fun terminateProcess() {
        // 1. Terminate Terminal Process
        try {
            val process = terminalEditor?.terminalWidget?.processTtyConnector?.process
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to terminate terminal process: ${e.message}")
        }
    }
}

package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.session.TurnSnapshot
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditor
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditorProvider
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLinkFilter
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog
import ai.opencode.ide.jetbrains.util.PathUtil
import ai.opencode.ide.jetbrains.util.PortFinder
import ai.opencode.ide.jetbrains.util.ProcessAuthDetector
import ai.opencode.ide.jetbrains.web.OpenCodeWebVirtualFile
import ai.opencode.ide.jetbrains.web.WebModeSupport

import com.google.gson.JsonElement
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil

import org.jetbrains.plugins.terminal.TerminalView

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    private enum class ConnectionMode { NONE, TERMINAL, WEB, REMOTE }

    companion object {
        private const val OPEN_CODE_TAB_PREFIX = "OpenCode"
        private const val RETRY_INTERVAL_MS = 5000L
        private const val BARRIER_TIMEOUT_MS = 2000L
    }

    private var hostname: String = "127.0.0.1"
    private var port: Int? = null
    private var username: String? = null
    private var password: String? = null
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var lastMode: ConnectionMode = ConnectionMode.NONE
    private var wasEverConnected = false
    private var remoteReconnectFailures = 0
    private var remoteReconnectDialogShown = false

    private var terminalVirtualFile: OpenCodeTerminalVirtualFile? = null
    private var terminalEditor: OpenCodeTerminalFileEditor? = null
    private var webVirtualFile: OpenCodeWebVirtualFile? = null

    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null
    
    // Turn state: keyed by sessionId
    private val turnMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val turnPendingPayloads = java.util.concurrent.ConcurrentHashMap<String, List<FileDiff>>()
    private val turnSnapshots = java.util.concurrent.ConcurrentHashMap<String, TurnSnapshot>()
    private val turnIdleWaiting = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val turnBarrierTasks = java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val turnLastTriggerTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val sessionManager: SessionManager get() = _sessionManager ?: project.service()
    private val diffViewerService: DiffViewerService get() = _diffViewerService ?: project.service()

    // Test hooks
    private var _sessionManager: SessionManager? = null
    private var _diffViewerService: DiffViewerService? = null

    internal fun setTestDeps(sm: SessionManager, dvs: DiffViewerService, client: OpenCodeApiClient) {
        _sessionManager = sm
        _diffViewerService = dvs
        apiClient = client
    }
    
    internal var invokeLater: (Runnable) -> Unit = { 
        ApplicationManager.getApplication().invokeLater(it) 
    }

    // ==================== Public API ====================

    fun focusOrCreateTerminal(interactive: Boolean = false) {
        val hasUI = terminalVirtualFile != null || webVirtualFile != null
        val running = port?.let { PortFinder.isOpenCodeRunningOnPort(it, hostname, username, password) } ?: false
        if (isConnected.get() && !running) isConnected.set(false)
        when {
            isConnected.get() && hasUI -> if (interactive) focusTerminalUI()
            interactive && port != null -> showReconnectOrNewDialog(running)
            !interactive && running && port != null -> restoreUiForMode()
            !interactive && !running && port != null -> showDisconnectedDialog(false)
            else -> showConnectionDialog()
        }
    }
    
    private fun showDisconnectedDialog(interactive: Boolean) {
        if (!interactive || lastMode == ConnectionMode.NONE) return
        ApplicationManager.getApplication().invokeLater {
            val res = Messages.showYesNoCancelDialog(
                project,
                "Server at $hostname:$port not running. Restart?",
                "OpenCode",
                "Restart",
                "New",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (res == Messages.YES) restartServer(lastMode)
            else if (res == Messages.NO) {
                disconnectAndReset()
                showConnectionDialog()
            }
        }
    }

    fun focusOrCreateTerminalAndPaste(text: String) {
        if (text.isBlank() || project.isDisposed) return
        if (apiClient == null && terminalEditor == null) { ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "OpenCode not running.", "OpenCode") }; return }
        schedulePasteAttempt(text, 20, 100L)
        ApplicationManager.getApplication().invokeLater { focusTerminalUI() }
    }

    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false
        apiClient?.let { client -> AppExecutorUtil.getAppExecutorService().submit { val ok = try { client.tuiAppendPrompt(text) } catch (_: Exception) { false }; if (!ok) ApplicationManager.getApplication().invokeLater { terminalEditor?.terminalWidget?.ttyConnector?.write(text.toByteArray()) } }; return true }
        terminalEditor?.terminalWidget?.ttyConnector?.let { it.write(text.toByteArray()); return true }
        return false
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener); listener(isConnected.get())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) { connectionListeners.remove(listener) }

    // ==================== Event Handling & Barrier ====================

    internal fun handleEvent(event: OpenCodeEvent) {
        if (event !is MessagePartUpdatedEvent && event !is UnknownEvent) logger.info("[OpenCode] SSE: ${event.type}")
        when (event) {
            is SessionStatusEvent -> {
                val sId = event.properties.sessionID
                val status = event.properties.status
                if (status.isBusy()) {
                    val started = sessionManager.onTurnStart()
                    if (started) clearTurnState(sId)
                } else if (status.isIdle()) {
                    // Capture snapshot BEFORE attempting barrier
                    val snapshot = sessionManager.onTurnEnd()
                    if (snapshot != null) {
                        turnSnapshots[sId] = snapshot
                        logger.info("[OpenCode] Turn #${snapshot.turnNumber} snapshot captured")
                    }
                    turnIdleWaiting[sId] = true
                    attemptBarrierTrigger(sId)
                }
            }
            is SessionIdleEvent -> {
                val sId = event.properties.sessionID
                val snapshot = sessionManager.onTurnEnd()
                if (snapshot != null) {
                    turnSnapshots[sId] = snapshot
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} snapshot captured (via idle event)")
                }
                turnIdleWaiting[sId] = true
                attemptBarrierTrigger(sId)
            }
            is FileEditedEvent -> sessionManager.onFileEdited(event.properties.file)
            is MessageUpdatedEvent -> {
                val info = event.properties.info
                if (info.role == null || info.role == "assistant") {
                    recordTurnMessageId(info.sessionID, info.id)
                }
            }
            is MessagePartUpdatedEvent -> extractPartMessageInfo(event.properties.part)?.let { 
                recordTurnMessageId(it.sessionId, it.messageId) 
            }
            is CommandExecutedEvent -> recordTurnMessageId(event.properties.sessionID, event.properties.messageID)
            is SessionDiffEvent -> if (event.properties.diff.isNotEmpty()) {
                turnPendingPayloads[event.properties.sessionID] = event.properties.diff
                attemptBarrierTrigger(event.properties.sessionID)
            }
            else -> {}
        }
    }

    private fun clearTurnState(sessionId: String) {
        val oldSnapshot = turnSnapshots.remove(sessionId)
        turnMessageIds.remove(sessionId)
        turnPendingPayloads.remove(sessionId)
        turnIdleWaiting.remove(sessionId)
        turnBarrierTasks.remove(sessionId)?.cancel(false)
        turnLastTriggerTimes.remove(sessionId)
        
        if (oldSnapshot != null) {
            logger.info("[OpenCode] Turn state cleared (was Turn #${oldSnapshot.turnNumber})")
        } else {
            logger.info("[OpenCode] Turn state cleared (no previous snapshot)")
        }
    }

    private fun recordTurnMessageId(sessionId: String, messageId: String) {
        if (turnMessageIds.containsKey(sessionId)) return
        turnMessageIds[sessionId] = messageId
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: sessionManager.getCurrentTurnNumber()
        logger.info("[OpenCode] Turn #$turnNum MessageID: $messageId")
        attemptBarrierTrigger(sessionId)
    }

    private fun attemptBarrierTrigger(sessionId: String) {
        val ready = turnIdleWaiting[sessionId] == true
        val hasId = turnMessageIds.containsKey(sessionId)
        val hasPayload = turnPendingPayloads.containsKey(sessionId)
        val snapshot = turnSnapshots[sessionId]
        
        logger.debug("[OpenCode] Barrier check: ready=$ready, hasId=$hasId, hasPayload=$hasPayload, hasSnapshot=${snapshot != null}")
        
        if (ready && (hasId || hasPayload)) {
            turnIdleWaiting[sessionId] = false
            turnBarrierTasks.remove(sessionId)?.cancel(false)
            if (snapshot != null) {
                logger.info("[OpenCode] Turn #${snapshot.turnNumber} Barrier triggered → fetching diffs")
                triggerDiffFetch(sessionId, snapshot)
            } else {
                logger.warn("[OpenCode] Barrier triggered but no snapshot available!")
            }
        } else if (ready) {
            scheduleBarrierTimeout(sessionId)
        }
    }

    private fun scheduleBarrierTimeout(sessionId: String) {
        if (turnBarrierTasks.containsKey(sessionId)) return
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: "?"
        logger.debug("[OpenCode] Turn #$turnNum Barrier timeout scheduled (${BARRIER_TIMEOUT_MS}ms)")
        val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val snapshot = turnSnapshots[sessionId]
            if (turnIdleWaiting[sessionId] == true && snapshot != null) {
                logger.warn("[OpenCode] Turn #${snapshot.turnNumber} Barrier timeout → forcing diff fetch")
                turnIdleWaiting[sessionId] = false
                triggerDiffFetch(sessionId, snapshot)
            }
            turnBarrierTasks.remove(sessionId)
        }, BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        turnBarrierTasks[sessionId] = task
    }

    private fun triggerDiffFetch(sessionId: String, snapshot: TurnSnapshot) {
        val now = System.currentTimeMillis()
        if (now - (turnLastTriggerTimes[sessionId] ?: 0) < 1500) {
            logger.debug("[OpenCode] Turn #${snapshot.turnNumber} Debounced (too soon)")
            return
        }
        turnLastTriggerTimes[sessionId] = now
        fetchAndShowDiffs(sessionId, snapshot)
    }

    private fun forceVfsRefresh(diffs: List<FileDiff>) {
        if (diffs.isEmpty()) return
        try {
            val files = diffs.mapNotNull { diff -> 
                PathUtil.resolveProjectPath(project, diff.file)?.let { 
                    val ioFile = java.io.File(it)
                    // If file is deleted, we must refresh the parent directory to detect deletion
                    if (!ioFile.exists()) ioFile.parentFile else ioFile
                } 
            }
            if (files.isNotEmpty()) {
                logger.info("[OpenCode] Forcing VFS refresh for ${files.size} paths: ${files.map { it.absolutePath }}")
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(files, false, false, null)
            }
        } catch (e: Exception) {
            logger.debug("[OpenCode] VFS refresh skipped: ${e.message}")
        }
    }

    private fun fetchAndShowDiffs(sessionId: String, snapshot: TurnSnapshot) {
        val client = apiClient ?: return
        val path = project.basePath ?: return
        val messageId = turnMessageIds[sessionId]
        val payload = turnPendingPayloads[sessionId]
        
        logger.info("[OpenCode] Turn #${snapshot.turnNumber} fetchAndShowDiffs: messageId=$messageId, payloadSize=${payload?.size ?: 0}")
        
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                var diffs: List<FileDiff> = emptyList()
                
                // Priority 1: Fetch by messageId (most accurate)
                if (messageId != null) {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Fetching diffs for messageId: $messageId")
                    diffs = client.getSessionDiff(sessionId, path, messageId)
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} API returned ${diffs.size} diffs")
                }
                
                // Priority 2: Use cached SSE payload
                if (diffs.isEmpty() && payload != null) {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Using SSE payload (${payload.size} files)")
                    diffs = payload
                }
                
                // Priority 3: Fallback to session summary (last resort)
                if (diffs.isEmpty() && messageId == null) {
                    logger.warn("[OpenCode] Turn #${snapshot.turnNumber} No messageId or payload, trying session summary")
                    client.getSession(sessionId, path)?.summary?.diffs?.let { diffs = it }
                }
                
                // Process using the snapshot
                if (diffs.isNotEmpty()) {
                    // Force VFS refresh to ensure we catch all disk changes immediately
                    forceVfsRefresh(diffs)
                    
                    val entries = sessionManager.processDiffs(diffs, snapshot)
                    if (entries.isNotEmpty()) {
                        // Resolve before content on background thread
                        val resolvedEntries = entries.map { entry ->
                            entry.copy(resolvedBefore = sessionManager.resolveBeforeContent(entry.file, entry.diff, snapshot))
                        }
                        logger.info("[OpenCode] Turn #${snapshot.turnNumber} Showing ${resolvedEntries.size} diffs")
                        invokeLater {
                            if (!project.isDisposed) diffViewerService.showMultiFileDiff(resolvedEntries)
                        }
                    } else {
                        logger.info("[OpenCode] Turn #${snapshot.turnNumber} No entries after processing")
                    }
                } else {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} No diffs received from any source")
                }
            } catch (e: Exception) {
                logger.error("[OpenCode] Turn #${snapshot.turnNumber} Diff fetch error", e)
            } finally {
                turnPendingPayloads.remove(sessionId)
            }
        }
    }

    // ==================== Lifecycle & Connection ====================

    private fun initializeApiClient(host: String, port: Int) {
        val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host
        apiClient = OpenCodeApiClient(apiHost, port, username, password)
    }

    private fun startConnectionManager() {
        if (connectionManagerTask?.isDone == false || port == null || apiClient == null) return
        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (!project.isDisposed) tryConnect() }, 0, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect() {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return
        try {
            apiClient?.let {
                if (it.checkHealth(project.basePath!!)) {
                    remoteReconnectFailures = 0
                    wasEverConnected = true
                    connectToSse()
                } else {
                    handleConnectionFailure()
                }
            }
        } catch (_: Exception) {
            handleConnectionFailure()
        } finally {
            isConnecting.set(false)
        }
    }

    private fun handleConnectionFailure() {
        if (lastMode == ConnectionMode.NONE || !wasEverConnected) return
        if (++remoteReconnectFailures >= 2 && !remoteReconnectDialogShown) { remoteReconnectDialogShown = true; showRemoteReconnectDialog() }
    }

    private fun showRemoteReconnectDialog() {
        ApplicationManager.getApplication().invokeLater { if (isConnected.get()) return@invokeLater; if (Messages.showYesNoCancelDialog(project, "Connection lost. Reconnect?", "OpenCode", "Reconnect", "New", "Cancel", Messages.getWarningIcon()) == Messages.NO) { disconnectAndReset(); showConnectionDialog() } else if (ApplicationManager.getApplication().isExitInProgress) disconnectAndReset() }
    }

    private fun connectToSse() {
        sseListener?.disconnect(); apiClient?.let { sseListener = it.createEventListener(project.basePath!!, { handleEvent(it) }, { updateConnectionState(false) }, { updateConnectionState(true) }, { updateConnectionState(false) }).apply { connect() } }
    }

    private fun updateConnectionState(connected: Boolean) { if (isConnected.getAndSet(connected) != connected) connectionListeners.forEach { it(connected) } }
    override fun dispose() { disconnectAndReset(); OpenCodeTerminalFileEditorProvider.clearAll() }

    private fun disconnectAndReset() {
        connectionManagerTask?.cancel(true); sseListener?.disconnect(); isConnected.set(false); isConnecting.set(false)
        turnMessageIds.clear(); turnPendingPayloads.clear(); turnSnapshots.clear(); turnIdleWaiting.clear(); turnBarrierTasks.values.forEach { it.cancel(false) }; turnBarrierTasks.clear()
        terminateProcess()
        terminalVirtualFile?.let { OpenCodeTerminalFileEditorProvider.unregisterWidget(it) }
        terminalVirtualFile = null; terminalEditor = null; webVirtualFile = null; port = null; hostname = "127.0.0.1"; apiClient = null
    }

    private fun terminateProcess() {
        try {
            val process = terminalEditor?.terminalWidget?.processTtyConnector?.process
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    private fun showReconnectOrNewDialog(running: Boolean) {
        val msg = if (running) "Session active. Restore UI?" else "Server dead. Restart?"
        ApplicationManager.getApplication().invokeLater {
            val res = Messages.showYesNoCancelDialog(project, msg, "OpenCode", if (running) "Restore" else "Restart", "New", "Cancel", Messages.getQuestionIcon())
            if (res == Messages.YES) if (running) restoreUiForMode() else restartServer(lastMode)
            else if (res == Messages.NO) { disconnectAndReset(); showConnectionDialog() }
        }
    }

    private fun showConnectionDialog() {
        AppExecutorUtil.getAppExecutorService().submit {
            val suggested = PortFinder.findAvailablePort()
            ApplicationManager.getApplication().invokeLater {
                OpenCodeConnectDialog.show(project, suggested)?.let { processConnectionChoice(it.hostname, it.port, it.password, it.useWebInterface) }
            }
        }
    }

    private fun processConnectionChoice(h: String, p: Int, pwd: String?, web: Boolean) {
        val local = h in listOf("0.0.0.0", "127.0.0.1", "localhost")
        AppExecutorUtil.getAppExecutorService().submit {
            val a = if (!pwd.isNullOrBlank()) ProcessAuthDetector.ServerAuth("opencode", pwd) else if (local) ProcessAuthDetector.detectAuthForPort(p) else ProcessAuthDetector.ServerAuth("opencode", null)
            val ok = if (local) PortFinder.isOpenCodeRunningOnPort(p, h, a.username, a.password) else true
            ApplicationManager.getApplication().invokeLater {
                lastMode = if (!ok) (if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL) else (if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL)
                if (ok) connectToExistingServer(h, p, a, web || !local, web)
                else if (web) createWebTerminal(h, p, a.password) else createLocalTerminal(h, p, a.password)
            }
        }
    }

    private fun connectToExistingServer(h: String, p: Int, a: ProcessAuthDetector.ServerAuth, ui: Boolean, web: Boolean) {
        hostname = h; port = p; username = a.username; password = a.password
        if (ui) { if (web) createWebUI(h, p) else createTerminalUI(h, p, a.password, false) }
        initializeApiClient(h, p); startConnectionManager()
    }

    private fun createWebUI(h: String, p: Int) {
        webVirtualFile = WebModeSupport.openWebTab(project, h, p, password) { if (webVirtualFile == null) terminateProcess() }
    }

    private fun createWebTerminal(h: String, p: Int, pwd: String?) {
        if (!ensureOpenCodeCliAvailable()) return
        hostname = h; port = p; lastMode = ConnectionMode.WEB; createTerminalUI(h, p, pwd, true)
        AppExecutorUtil.getAppExecutorService().submit { if (PortFinder.waitForPort(p, h, 30000, true)) { ApplicationManager.getApplication().invokeLater { createWebUI(h, p) }; initializeApiClient(h, p); startConnectionManager() } else terminateProcess() }
    }

    private fun createLocalTerminal(h: String, p: Int, pwd: String?) {
        if (!ensureOpenCodeCliAvailable()) return
        hostname = h; port = p; lastMode = ConnectionMode.TERMINAL
        AppExecutorUtil.getAppExecutorService().submit { if (PortFinder.waitForPort(p, h)) { initializeApiClient(h, p); startConnectionManager() } }
        createTerminalUI(h, p, pwd)
    }

    private fun createTerminalUI(h: String, p: Int, pwd: String?, cont: Boolean = true) {
        if (!ensureOpenCodeCliAvailable()) return
        val t = "$OPEN_CODE_TAB_PREFIX($p)"
        val w = TerminalView.getInstance(project).createLocalShellWidget(project.basePath, t)
        OpenCodeTerminalLinkFilter.install(project, w)
        val f = OpenCodeTerminalVirtualFile(t)
        terminalVirtualFile = f; OpenCodeTerminalFileEditorProvider.registerWidget(f, w)
        ApplicationManager.getApplication().invokeLater {
            terminalEditor = FileEditorManager.getInstance(project).openFile(f, true).firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor
            terminalEditor?.let { w.executeCommand(buildOpenCodeCommand(h, p, pwd, cont)); pinTerminalTab(f) }
        }
    }

    private fun buildOpenCodeCommand(h: String, p: Int, pwd: String?, cont: Boolean): String {
        val base = "opencode --hostname $h --port $p${if (cont) " --continue" else ""}"
        if (pwd.isNullOrBlank()) return base
        return if (isWindows()) "cmd /c \"set \"OPENCODE_SERVER_PASSWORD=${pwd.replace("\"", "\\\"")}\" && $base\"" else "OPENCODE_SERVER_PASSWORD='${pwd.replace("'", "'\\''")}' $base"
    }

    private fun ensureOpenCodeCliAvailable(): Boolean {
        val cmds = if (isWindows()) listOf(listOf("cmd", "/c", "where", "opencode")) else listOf(listOf("which", "opencode"), listOf("sh", "-lc", "command -v opencode"))
        val ok = cmds.any { try { CapturingProcessHandler(GeneralCommandLine(it)).runProcess(1500).exitCode == 0 } catch (_: Exception) { false } }
        if (!ok) ApplicationManager.getApplication().invokeLater { Messages.showErrorDialog(project, "OpenCode CLI not found.", "Error") }
        return ok
    }

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("windows")
    private fun isLinux() = System.getProperty("os.name", "").lowercase().contains("linux")
    private fun pinTerminalTab(f: VirtualFile) { try { FileEditorManagerEx.getInstanceEx(project).currentWindow?.setFilePinned(f, true) } catch (_: Exception) {} }
    private fun restartServer(m: ConnectionMode) { val h = hostname; val p = port ?: return; val pwd = password; disconnectAndReset(); hostname = h; port = p; password = pwd; lastMode = m; if (m == ConnectionMode.WEB) createWebTerminal(h, p, pwd) else createLocalTerminal(h, p, pwd) }
    private fun focusTerminalUI() { terminalVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } ?: webVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } }
    private fun restoreUiForMode() { when (lastMode) { ConnectionMode.TERMINAL -> ensureTerminalUi(); ConnectionMode.WEB -> ensureWebUi(); ConnectionMode.REMOTE -> showHeadlessStatusDialog(); else -> showConnectionDialog() } }
    private fun ensureTerminalUi() { val f = terminalVirtualFile; if (f != null && OpenCodeTerminalFileEditorProvider.hasWidget(f)) focusTerminalUI() else createTerminalUI(hostname, port ?: return, password, false) }
    private fun ensureWebUi() { if (webVirtualFile != null) focusTerminalUI() else createWebUI(hostname, port ?: return) }
    private fun showHeadlessStatusDialog() { ApplicationManager.getApplication().invokeLater { if (Messages.showYesNoDialog(project, "Connected to $hostname:$port (Headless). Disconnect?", "OpenCode", "Disconnect", "Keep", Messages.getInformationIcon()) == Messages.YES) disconnectAndReset() } }
    private fun schedulePasteAttempt(t: String, l: Int, d: Long) { if (l <= 0 || project.isDisposed) return; AppExecutorUtil.getAppScheduledExecutorService().schedule({ ApplicationManager.getApplication().invokeLater { if (!project.isDisposed && !pasteToTerminal(t)) schedulePasteAttempt(t, l - 1, d) } }, d, TimeUnit.MILLISECONDS) }
    private fun extractPartMessageInfo(p: JsonElement): PartMessageInfo? { if (!p.isJsonObject) return null; val o = p.asJsonObject; val mId = o.get("messageID")?.asString; val sId = o.get("sessionID")?.asString; return if (mId != null && sId != null) PartMessageInfo(sId, mId) else null }
    private data class PartMessageInfo(val sessionId: String, val messageId: String)
}

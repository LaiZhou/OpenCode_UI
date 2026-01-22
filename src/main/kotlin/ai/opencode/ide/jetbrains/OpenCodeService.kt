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
    private val turnMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val turnPendingPayloads = java.util.concurrent.ConcurrentHashMap<String, List<FileDiff>>()
    private val turnIdleWaiting = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val turnBarrierTasks = java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val turnLastTriggerTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val sessionManager: SessionManager get() = project.service()
    private val diffViewerService: DiffViewerService get() = project.service()

    fun focusOrCreateTerminal(interactive: Boolean = false) {
        val running = port?.let { PortFinder.isOpenCodeRunningOnPort(it, hostname, username, password) } ?: false
        if (isConnected.get() && !running) isConnected.set(false)
        when {
            isConnected.get() && (terminalVirtualFile != null || webVirtualFile != null) -> if (interactive) focusTerminalUI()
            interactive && port != null -> showReconnectOrNewDialog(running)
            !interactive && running && port != null -> restoreUiForMode()
            !interactive && !running && port != null -> handleDisconnectedProcess(false)
            else -> showConnectionDialog()
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

    private fun handleEvent(event: OpenCodeEvent) {
        if (event !is MessagePartUpdatedEvent && event !is UnknownEvent) logger.info("[OpenCode] SSE Event: ${event.type}")
        when (event) {
            is SessionStatusEvent -> {
                val status = event.properties.status
                if (sessionManager.onSessionStatusChanged(event.properties.sessionID, status) && status.isBusy()) clearTurnState(event.properties.sessionID)
                else if (status.isIdle()) { turnIdleWaiting[event.properties.sessionID] = true; attemptBarrierTrigger(event.properties.sessionID, "status.idle") }
            }
            is SessionIdleEvent -> { turnIdleWaiting[event.properties.sessionID] = true; attemptBarrierTrigger(event.properties.sessionID, "session.idle") }
            is FileEditedEvent -> sessionManager.onFileEdited(event.properties.file)
            is MessageUpdatedEvent -> { val info = event.properties.info; if (info.role == null || info.role == "assistant") recordTurnMessageId(info.sessionID, info.id, "message.updated") }
            is MessagePartUpdatedEvent -> extractPartMessageInfo(event.properties.part)?.let { recordTurnMessageId(it.sessionId, it.messageId, "part.updated") }
            is CommandExecutedEvent -> recordTurnMessageId(event.properties.sessionID, event.properties.messageID, "command.executed")
            is SessionDiffEvent -> if (event.properties.diff.isNotEmpty()) { turnPendingPayloads[event.properties.sessionID] = event.properties.diff; attemptBarrierTrigger(event.properties.sessionID, "session.diff") }
            else -> {}
        }
    }

    private fun clearTurnState(sessionId: String) {
        turnMessageIds.remove(sessionId); turnPendingPayloads.remove(sessionId); turnIdleWaiting.remove(sessionId)
        turnBarrierTasks.remove(sessionId)?.cancel(false)
        logger.info("[OpenCode] Turn state cleared for session: $sessionId")
    }

    private fun recordTurnMessageId(sessionId: String, messageId: String, source: String) {
        if (turnMessageIds.containsKey(sessionId)) return
        turnMessageIds[sessionId] = messageId
        logger.info("[OpenCode] MessageID recorded: $messageId ($source)")
        attemptBarrierTrigger(sessionId, source)
    }

    private fun attemptBarrierTrigger(sessionId: String, source: String) {
        val ready = turnIdleWaiting[sessionId] == true
        if (ready && (turnMessageIds.containsKey(sessionId) || turnPendingPayloads.containsKey(sessionId))) {
            turnIdleWaiting[sessionId] = false; turnBarrierTasks.remove(sessionId)?.cancel(false)
            logger.info("[OpenCode] Barrier TRIGGER for $sessionId")
            triggerDiffFetch(sessionId)
        } else if (ready) scheduleBarrierTimeout(sessionId)
    }

    private fun scheduleBarrierTimeout(sessionId: String) {
        if (turnBarrierTasks.containsKey(sessionId)) return
        val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (turnIdleWaiting[sessionId] == true) { logger.warn("[OpenCode] Barrier TIMEOUT: Forcing fetch."); turnIdleWaiting[sessionId] = false; triggerDiffFetch(sessionId) }
            turnBarrierTasks.remove(sessionId)
        }, BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        turnBarrierTasks[sessionId] = task
    }

    private fun triggerDiffFetch(sessionId: String) {
        val now = System.currentTimeMillis()
        if (now - (turnLastTriggerTimes[sessionId] ?: 0) < 1500) return
        turnLastTriggerTimes[sessionId] = now; fetchAndShowDiffs(sessionId)
    }

    private fun fetchAndShowDiffs(sessionId: String) {
        val client = apiClient ?: return
        val path = project.basePath ?: return
        val messageId = turnMessageIds[sessionId]
        val payload = turnPendingPayloads[sessionId]
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                var diffs: List<FileDiff> = if (messageId != null) { logger.info("[OpenCode] Fetching diffs for ID: $messageId"); client.getSessionDiff(sessionId, path, messageId) } else emptyList()
                if (diffs.isEmpty() && payload != null) { logger.info("[OpenCode] Using cached SSE payload"); diffs = payload }
                if (diffs.isEmpty() && messageId == null) { logger.warn("[OpenCode] No ID or Payload, fallback to Summary"); client.getSession(sessionId, path)?.summary?.diffs?.let { diffs = it } }
                if (diffs.isNotEmpty()) processDiffs(sessionId, diffs)
            } catch (e: Exception) { logger.error("[OpenCode] Fetch error", e) } finally { turnPendingPayloads.remove(sessionId) }
        }
    }

    private fun processDiffs(sessionId: String, diffs: List<FileDiff>) {
        sessionManager.clearDiffs()
        val filtered = sessionManager.filterNewDiffs(diffs)
        if (filtered.isNotEmpty()) {
            val entries = filtered.map { DiffEntry(sessionId, turnMessageIds[sessionId], null, it, System.currentTimeMillis()) }
            sessionManager.onDiffReceived(DiffBatch(sessionId, turnMessageIds[sessionId], filtered))
            sessionManager.onSessionCompleted(sessionId, filtered.size)
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) diffViewerService.showMultiFileDiff(entries) }
        }
    }

    private fun initializeApiClient(host: String, port: Int) { val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host; apiClient = OpenCodeApiClient(apiHost, port, username, password).also { sessionManager.setApiClient(it) } }
    private fun startConnectionManager() { if (connectionManagerTask?.isDone == false || port == null || apiClient == null) return; connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (!project.isDisposed) tryConnect() }, 0, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS) }
    private fun tryConnect() { if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return; try { apiClient?.let { if (it.checkHealth(project.basePath!!)) { remoteReconnectFailures = 0; wasEverConnected = true; sessionManager.refreshActiveSession(); connectToSse() } else handleConnectionFailure() } } catch (_: Exception) { handleConnectionFailure() } finally { isConnecting.set(false) } }
    private fun handleConnectionFailure() { if (lastMode == ConnectionMode.NONE || !wasEverConnected) return; if (++remoteReconnectFailures >= 2 && !remoteReconnectDialogShown) { remoteReconnectDialogShown = true; showRemoteReconnectDialog() } }
    private fun showRemoteReconnectDialog() { ApplicationManager.getApplication().invokeLater { if (isConnected.get()) return@invokeLater; if (Messages.showYesNoCancelDialog(project, "Connection lost. Reconnect?", "OpenCode", "Reconnect", "New", "Cancel", Messages.getWarningIcon()) == Messages.NO) { disconnectAndReset(); showConnectionDialog() } else if (ApplicationManager.getApplication().isExitInProgress) disconnectAndReset() } }
    private fun connectToSse() { sseListener?.disconnect(); apiClient?.let { sseListener = it.createEventListener(project.basePath!!, { handleEvent(it) }, { updateConnectionState(false) }, { updateConnectionState(true) }, { updateConnectionState(false) }).apply { connect() } } }
    private fun updateConnectionState(connected: Boolean) { if (isConnected.getAndSet(connected) != connected) connectionListeners.forEach { it(connected) } }
    override fun dispose() { disconnectAndReset(); OpenCodeTerminalFileEditorProvider.clearAll() }
    private fun disconnectAndReset() { connectionManagerTask?.cancel(true); sseListener?.disconnect(); isConnected.set(false); isConnecting.set(false); turnMessageIds.clear(); turnPendingPayloads.clear(); turnIdleWaiting.clear(); turnBarrierTasks.values.forEach { it.cancel(false) }; turnBarrierTasks.clear(); terminateProcess(); terminalVirtualFile?.let { OpenCodeTerminalFileEditorProvider.unregisterWidget(it) }; terminalVirtualFile = null; terminalEditor = null; webVirtualFile = null; port = null; hostname = "127.0.0.1"; apiClient = null }
    private fun terminateProcess() { try { terminalEditor?.terminalWidget?.processTtyConnector?.process?.let { if (it.isAlive) { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() } } } catch (_: Exception) {} }
    private fun showReconnectOrNewDialog(running: Boolean) { val msg = if (running) "Session active. Restore UI?" else "Server dead. Restart?"; ApplicationManager.getApplication().invokeLater { val res = Messages.showYesNoCancelDialog(project, msg, "OpenCode", if (running) "Restore" else "Restart", "New", "Cancel", Messages.getQuestionIcon()); if (res == Messages.YES) if (running) restoreUiForMode() else restartServer(lastMode) else if (res == Messages.NO) { disconnectAndReset(); showConnectionDialog() } } }
    private fun showConnectionDialog() { AppExecutorUtil.getAppExecutorService().submit { val s = PortFinder.findAvailablePort(); ApplicationManager.getApplication().invokeLater { OpenCodeConnectDialog.show(project, s)?.let { processConnectionChoice(it.hostname, it.port, it.password, it.useWebInterface) } } } }
    private fun processConnectionChoice(h: String, p: Int, pwd: String?, web: Boolean) { val local = h in listOf("0.0.0.0", "127.0.0.1", "localhost"); AppExecutorUtil.getAppExecutorService().submit { val a = if (!pwd.isNullOrBlank()) ProcessAuthDetector.ServerAuth("opencode", pwd) else if (local) ProcessAuthDetector.detectAuthForPort(p) else ProcessAuthDetector.ServerAuth("opencode", null); val ok = if (local) PortFinder.isOpenCodeRunningOnPort(p, h, a.username, a.password) else true; ApplicationManager.getApplication().invokeLater { lastMode = if (!ok) (if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL) else (if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL); if (ok) connectToExistingServer(h, p, a, web || !local, web) else if (web) createWebTerminal(h, p, a.password) else createLocalTerminal(h, p, a.password) } } }
    private fun connectToExistingServer(h: String, p: Int, a: ProcessAuthDetector.ServerAuth, ui: Boolean, web: Boolean) { hostname = h; port = p; username = a.username; password = a.password; if (ui) { if (web) createWebUI(h, p) else createTerminalUI(h, p, a.password, false) }; initializeApiClient(h, p); startConnectionManager() }
    private fun createWebUI(h: String, p: Int) { webVirtualFile = WebModeSupport.openWebTab(project, h, p, password) { if (webVirtualFile == null) terminateProcess() } }
    private fun createWebTerminal(h: String, p: Int, pwd: String?) { if (!ensureOpenCodeCliAvailable()) return; hostname = h; port = p; lastMode = ConnectionMode.WEB; createTerminalUI(h, p, pwd, true); AppExecutorUtil.getAppExecutorService().submit { if (PortFinder.waitForPort(p, h, 30000, true)) { ApplicationManager.getApplication().invokeLater { createWebUI(h, p) }; initializeApiClient(h, p); startConnectionManager() } else terminateProcess() } }
    private fun createLocalTerminal(h: String, p: Int, pwd: String?) { if (!ensureOpenCodeCliAvailable()) return; hostname = h; port = p; lastMode = ConnectionMode.TERMINAL; AppExecutorUtil.getAppExecutorService().submit { if (PortFinder.waitForPort(p, h)) { initializeApiClient(h, p); startConnectionManager() } }; createTerminalUI(h, p, pwd) }
    private fun createTerminalUI(h: String, p: Int, pwd: String?, cont: Boolean = true) { if (!ensureOpenCodeCliAvailable()) return; val t = "$OPEN_CODE_TAB_PREFIX($p)"; val w = TerminalView.getInstance(project).createLocalShellWidget(project.basePath, t); OpenCodeTerminalLinkFilter.install(project, w); val f = OpenCodeTerminalVirtualFile(t); terminalVirtualFile = f; OpenCodeTerminalFileEditorProvider.registerWidget(f, w); ApplicationManager.getApplication().invokeLater { terminalEditor = FileEditorManager.getInstance(project).openFile(f, true).firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor; terminalEditor?.let { w.executeCommand(buildOpenCodeCommand(h, p, pwd, cont)); pinTerminalTab(f) } } }
    private fun buildOpenCodeCommand(h: String, p: Int, pwd: String?, cont: Boolean): String { val base = "opencode --hostname $h --port $p${if (cont) " --continue" else ""}"; return if (pwd.isNullOrBlank()) base else if (isWindows()) "cmd /c \"set \"OPENCODE_SERVER_PASSWORD=${pwd.replace("\"", "\\\"")}\" && $base\"" else "OPENCODE_SERVER_PASSWORD='${pwd.replace("'", "'\\''")}' $base" }
    private fun ensureOpenCodeCliAvailable(): Boolean { val cmds = if (isWindows()) listOf(listOf("cmd", "/c", "where", "opencode")) else listOf(listOf("which", "opencode"), listOf("sh", "-lc", "command -v opencode")); val ok = cmds.any { try { CapturingProcessHandler(GeneralCommandLine(it)).runProcess(1500).exitCode == 0 } catch (_: Exception) { false } }; if (!ok) ApplicationManager.getApplication().invokeLater { Messages.showErrorDialog(project, "OpenCode CLI not found.", "Error") }; return ok }
    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("windows")
    private fun isLinux() = System.getProperty("os.name", "").lowercase().contains("linux")
    private fun pinTerminalTab(f: VirtualFile) { try { FileEditorManagerEx.getInstanceEx(project).currentWindow?.setFilePinned(f, true) } catch (_: Exception) {} }
    private fun restartServer(m: ConnectionMode) { val h = hostname; val p = port ?: return; val pwd = password; disconnectAndReset(); hostname = h; port = p; password = pwd; lastMode = m; if (m == ConnectionMode.WEB) createWebTerminal(h, p, pwd) else createLocalTerminal(h, p, pwd) }
    private fun focusTerminalUI() { terminalVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } ?: webVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } }
    private fun restoreUiForMode() { when (lastMode) { ConnectionMode.TERMINAL -> ensureTerminalUi(); ConnectionMode.WEB -> ensureWebUi(); ConnectionMode.REMOTE -> showHeadlessStatusDialog(); else -> showConnectionDialog() } }
    private fun ensureTerminalUi() { val f = terminalVirtualFile; if (f != null && OpenCodeTerminalFileEditorProvider.hasWidget(f)) focusTerminalUI() else createTerminalUI(hostname, port ?: return, password, false) }
    private fun ensureWebUi() { if (webVirtualFile != null) focusTerminalUI() else createWebUI(hostname, port ?: return) }
    private fun showHeadlessStatusDialog() { ApplicationManager.getApplication().invokeLater { if (Messages.showYesNoDialog(project, "Connected to $hostname:$port (Headless). Disconnect?", "OpenCode", "Disconnect", "Keep", Messages.getInformationIcon()) == Messages.YES) disconnectAndReset() } }
    private fun handleDisconnectedProcess(interactive: Boolean) { if (!interactive || lastMode == ConnectionMode.NONE) return; ApplicationManager.getApplication().invokeLater { val res = Messages.showYesNoCancelDialog(project, "Server at $hostname:$port not running. Restart?", "OpenCode", "Restart", "New", "Cancel", Messages.getWarningIcon()); if (res == Messages.YES) restartServer(lastMode) else if (res == Messages.NO) { disconnectAndReset(); showConnectionDialog() } } }
    private fun schedulePasteAttempt(t: String, l: Int, d: Long) { if (l <= 0 || project.isDisposed) return; AppExecutorUtil.getAppScheduledExecutorService().schedule({ ApplicationManager.getApplication().invokeLater { if (!project.isDisposed && !pasteToTerminal(t)) schedulePasteAttempt(t, l - 1, d) } }, d, TimeUnit.MILLISECONDS) }
    private fun extractPartMessageInfo(p: JsonElement): PartMessageInfo? { if (!p.isJsonObject) return null; val o = p.asJsonObject; val mId = o.get("messageID")?.asString; val sId = o.get("sessionID")?.asString; return if (mId != null && sId != null) PartMessageInfo(sId, mId) else null }
    private data class PartMessageInfo(val sessionId: String, val messageId: String)
    fun addConnectionListener(l: (Boolean) -> Unit) { connectionListeners.add(l); l(isConnected.get()) }
    fun removeConnectionListener(l: (Boolean) -> Unit) { connectionListeners.remove(l) }
}

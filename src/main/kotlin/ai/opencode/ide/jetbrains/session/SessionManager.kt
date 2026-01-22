package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.runWriteAction
import com.intellij.ui.SystemNotifications
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages session state and tracks diffs.
 *
 * Key responsibilities:
 * - Track diffs with their associated sessionId/messageId
 * - Provide operations on specific diffs (view, accept, reject)
 * - Maintain mapping between files and their diff context
 */
@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SessionManager::class.java)

    // API client reference (set by OpenCodeService)
    private var apiClient: OpenCodeApiClient? = null

    // Current active session ID (the one that's busy or most recently active)
    private var activeSessionId: String? = null

    // Diff entries indexed by file path for quick lookup
    // Key: file path, Value: DiffEntry with full context (latest for that file)
    private val diffsByFile = ConcurrentHashMap<String, DiffEntry>()

    // All diff batches, indexed by sessionId
    // Key: sessionId, Value: list of DiffBatch from that session
    private val diffBatchesBySession = ConcurrentHashMap<String, MutableList<DiffBatch>>()

    // LocalHistory label captured when session becomes busy (AI starts working)
    private var baselineLabel: Label? = null

    // Flag to track if the session is currently in a busy cycle
    private var isCurrentlyBusy = false

    // Files edited by OpenCode during the current busy cycle.
    private val openCodeEditedFiles = ConcurrentHashMap<String, Boolean>()

    // Avoid spamming notifications during a single busy cycle.
    private var missingEditNoticeShown = false


    /**
     * Set the API client for server operations.
     */
    fun setApiClient(client: OpenCodeApiClient) {
        this.apiClient = client
    }

    /**
     * Handle file edited event.
     * Refreshes the file in the IDE virtual file system.
     * Note: LocalHistory labels are created at the start/end of a busy cycle.
     */
    fun onFileEdited(filePath: String) {
        openCodeEditedFiles[filePath] = true
        logger.info("[OpenCode Edit] OpenCode edited file: $filePath")
        refreshFiles(listOf(filePath))
    }

    /**
     * Create a LocalHistory snapshot for a completed OpenCode session.
     * Called when session becomes idle (AI finished one conversation turn).
     */
    fun onSessionCompleted(sessionId: String, fileCount: Int) {
        if (fileCount > 0) {
            createLocalHistoryLabel("OpenCode Modified After")
            logger.info("Created LocalHistory label: OpenCode Modified After (modified $fileCount files)")
        }
    }

    // ========== Diff Management ==========

    /**
     * Handle incoming diff batch from SSE event.
     * This is the main entry point for new diffs.
     */
    fun onDiffReceived(diffBatch: DiffBatch) {
        logger.info("Received diff batch: session=${diffBatch.sessionId}, " +
                "message=${diffBatch.messageId}, files=${diffBatch.diffs.size}")

        // Update active session
        activeSessionId = diffBatch.sessionId

        // Store the batch
        diffBatchesBySession.getOrPut(diffBatch.sessionId) { mutableListOf() }.add(diffBatch)

        // Convert to entries
        val entries = diffBatch.toEntries()

        entries.forEach { entry ->
            diffsByFile[entry.diff.file] = entry
        }
    }

    /**
     * Build the diff list to display for the current session.
     *
     * Strategy:
     * - Require file.edited events to identify OpenCode-touched files.
     * - Skip diff display entirely if no events were received.
     * - Resolve "before" from LocalHistory/server and "after" from disk content.
     */
    fun filterNewDiffs(diffs: List<FileDiff>): List<FileDiff> {
        logger.info("[Diff Filter] Server returned ${diffs.size} diffs, selecting OpenCode edits...")

        val editedFiles = openCodeEditedFiles.keys
        if (editedFiles.isEmpty()) {
            logger.warn("[Diff Filter] No file.edited events; skipping diff display")
            notifyMissingEditEvents(diffs.size)
            return emptyList()
        }

        logger.info("[Diff Filter] Using file.edited list: ${editedFiles.size} files")

        val serverDiffs = diffs.associateBy { it.file }
        val result = editedFiles.mapNotNull { filePath ->
            val serverDiff = serverDiffs[filePath]
            val serverBefore = serverDiff?.before ?: ""
            val serverAfter = serverDiff?.after ?: ""
            val additions = serverDiff?.additions ?: 0
            val deletions = serverDiff?.deletions ?: 0

            if (serverDiff == null) {
                logger.info("[Diff Filter] No server diff for $filePath; building from disk only")
            }

            val effectiveBefore = resolveBeforeContent(filePath, serverBefore)
            val effectiveAfter = resolveAfterContent(filePath, serverAfter, additions)

            if (effectiveBefore == effectiveAfter) {
                logger.info("[Diff Filter] DECISION: SKIP (before == after, no actual change)")
                logger.info("  File: $filePath")
                return@mapNotNull null
            }

            FileDiff(
                file = filePath,
                before = effectiveBefore,
                after = effectiveAfter,
                additions = additions,
                deletions = deletions
            )
        }

        logger.info("[Diff Filter] Result: ${result.size} new diffs out of ${diffs.size} total")
        return result
    }

    private fun notifyMissingEditEvents(serverDiffCount: Int) {
        if (missingEditNoticeShown || project.isDisposed) return
        missingEditNoticeShown = true

        // Notify once per busy cycle so users understand why diffs are hidden.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
            val title = "OpenCode Diff Skipped"
            val content = "No file.edited events received. Skipping ${serverDiffCount} server diff(s)."
            notificationGroup.createNotification(title, content, NotificationType.INFORMATION).notify(project)
        }
    }

    /**
     * Resolve the "after" content for display.
     * Prefer disk content to reflect the real working tree state, and fall back to server data if needed.
     */
    private fun resolveAfterContent(filePath: String, serverAfter: String, serverAdditions: Int): String {
        val diskContent = readDiskContent(filePath)
        if (diskContent != null) {
            return diskContent
        }

        if (serverAfter.isNotEmpty()) {
            return serverAfter
        }

        if (serverAdditions > 0) {
            logger.warn("[Diff Resolve] Missing disk content for $filePath but server reports additions")
        }

        return ""
    }
    
    private fun readDiskContent(filePath: String): String? {
        val absPath = toAbsolutePath(filePath) ?: return null
        val file = java.io.File(absPath)
        if (!file.exists() || !file.isFile) return null
        return readFileContent(file)
    }

    /**
     * Read file content using IDE's VirtualFileSystem to ensure correct encoding (e.g. GBK, UTF-8 with BOM).
     * Fallback to java.io.File if VirtualFile is not available.
     */
    private fun readFileContent(file: java.io.File): String {
        return try {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
            if (virtualFile != null) {
                com.intellij.openapi.application.runReadAction {
                    String(virtualFile.contentsToByteArray(), virtualFile.charset)
                }
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            logger.warn("Failed to read file content: ${file.path}", e)
            ""
        }
    }

    /**
     * Handle session status change.
     */
    fun onSessionStatusChanged(sessionId: String, status: SessionStatusType) {
        logger.info("Session status changed: $sessionId -> ${status.type}")

        if (status.isBusy()) {
            activeSessionId = sessionId
            
            // Initialize busy-cycle state only once to avoid duplicate resets
            // when multiple "busy" events are emitted during one generation.
            if (!isCurrentlyBusy) {
                isCurrentlyBusy = true
                openCodeEditedFiles.clear()
                missingEditNoticeShown = false
                // Create LocalHistory label BEFORE AI modifies files
                baselineLabel = createLocalHistoryLabel("OpenCode Modified Before")
            }
        } else {
            // Check if we are transitioning from busy to idle
            if (isCurrentlyBusy && status.isIdle()) {
                showSessionCompletedNotification(sessionId)
            }
            // Reset busy flag when session becomes idle/done/error
            isCurrentlyBusy = false
        }
    }

    
    /**
     * Resolve the "before" content for a file.
     * 
     * This is the SINGLE SOURCE OF TRUTH used by both:
     * - DiffViewerService (to display the left pane)
     * - rejectDiff() (to restore the file)
     * 
     * Priority:
     * 1. LocalHistory snapshot from session start
     * 2. Server returned 'before' (normal case)
     * 3. Git HEAD (final fallback for tracked files)
     * 4. Empty string (new untracked file)
     */
    fun resolveBeforeContent(filePath: String, serverBefore: String): String {
        // 1. Use LocalHistory snapshot from session start
        val localHistoryContent = loadLocalHistoryContent(filePath)
        if (localHistoryContent != null) {
            logger.info("[ResolveBefore] Using LocalHistory Baseline: $filePath (len=${localHistoryContent.length})")
            return localHistoryContent
        }
        
        // 2. Server provided content - trust it
        if (serverBefore.isNotEmpty()) {
            logger.info("[ResolveBefore] Using Server Content: $filePath (len=${serverBefore.length})")
            return serverBefore
        }
        
        // 3. Fallback to Git HEAD for tracked files
        val projectPath = project.basePath
        if (projectPath != null) {
            val headContent = loadGitHeadContent(filePath, projectPath)
            if (headContent != null) {
                logger.info("[ResolveBefore] Using Git HEAD: $filePath (len=${headContent.length})")
                return headContent
            }
        }
        
        // 4. New untracked file - empty is correct
        logger.info("[ResolveBefore] No content found (new file): $filePath")
        return ""
    }
    
    private fun loadLocalHistoryContent(filePath: String): String? {
        val label = baselineLabel ?: return null
        val absPath = toAbsolutePath(filePath) ?: return null
        val byteContent = try {
            label.getByteContent(absPath)
        } catch (e: Exception) {
            logger.warn("[ResolveBefore] LocalHistory lookup failed: $filePath", e)
            null
        }
        if (byteContent == null || byteContent.isDirectory) return null

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absPath)
        val charset = virtualFile?.charset ?: Charsets.UTF_8
        return try {
            String(byteContent.bytes, charset)
        } catch (e: Exception) {
            logger.warn("[ResolveBefore] LocalHistory decode failed: $filePath", e)
            null
        }
    }

    private fun loadGitHeadContent(filePath: String, projectPath: String): String? {
        return try {
            val commandLine = GeneralCommandLine()
                .withExePath("git")
                .withWorkDirectory(projectPath)
                .withParameters("show", "HEAD:$filePath")
            
            val handler = OSProcessHandler(commandLine)
            val output = handler.process.inputStream.bufferedReader().readText()
            handler.process.waitFor()
            if (handler.process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get all current diff entries (latest per file).
     */
    fun getAllDiffEntries(): List<DiffEntry> {
        return diffsByFile.values
            .toList()
            // Sort by timestamp ASCENDING to match the AI generation order (replay thought process)
            .sortedWith(compareBy<DiffEntry> { it.timestamp }.thenBy { it.diff.file })
    }

    /**
     * Get diff entry for a specific file.
     */
    fun getDiffForFile(filePath: String): DiffEntry? {
        return diffsByFile[filePath]
    }


    // ========== Accept/Reject Operations ==========

    /**
     * Accept a single file diff (git add).
     *
     * Strategy:
     * - Stage the current disk content
     * - If the file was deleted, use git add -A
     *
     * @return true if successful, false otherwise
     */
    fun acceptDiff(filePath: String): Boolean {
        val projectPath = project.basePath ?: return false

        logger.info("[Accept] Staging: $filePath")
        val entry = diffsByFile[filePath]
        if (entry == null) {
            logger.warn("[Accept] No diff entry found for: $filePath")
            return false
        }

        val absPath = toAbsolutePath(filePath)
        if (absPath == null) {
            logger.warn("[Accept] Failed to resolve absolute path for: $filePath")
            return false
        }

        val ioFile = java.io.File(absPath)
        val gitArgs = if (ioFile.exists()) listOf("add", filePath) else listOf("add", "-A", filePath)
        val success = runGitCommand(gitArgs, projectPath)

        if (success) {
            diffsByFile.remove(filePath)
            removeFileFromBatches(filePath)
            logger.info("[Accept] Successfully staged: $filePath")
        } else {
            logger.warn("[Accept] Git command failed for: $filePath")
        }

        refreshFiles(listOf(filePath))
        return success
    }


    /**
     * Reject a single file diff.
     * 
     * Uses resolveBeforeContent() to get the restore target - this is the SAME
     * content shown in the Diff viewer's left pane, ensuring consistency.
     */
    fun rejectDiff(filePath: String): Boolean {
        val projectPath = project.basePath ?: return false
        
        logger.info("[Reject] Processing: $filePath")
        val entry = diffsByFile[filePath]
        if (entry == null) {
            logger.warn("[Reject] No diff entry found for file: $filePath")
            return false
        }
        
        val absPath = toAbsolutePath(filePath)
        if (absPath == null) {
            logger.warn("[Reject] Failed to resolve absolute path for: $filePath")
            return false
        }
        
        val ioFile = java.io.File(absPath)
        val isTracked = runGitCommand(listOf("ls-files", "--error-unmatch", filePath), projectPath)
        
        // Use the SAME logic as Diff display - what user sees is what they get
        val restoreContent = resolveBeforeContent(filePath, entry.diff.before)
        
        logger.info("[Reject Debug] context: isTracked=$isTracked, restoreContentLen=${restoreContent.length}, fileExists=${ioFile.exists()}")
        
        val success = try {
            var result = false
            ApplicationManager.getApplication().invokeAndWait {
                runWriteAction {
                    if (restoreContent.isNotEmpty()) {
                        // Normal case: restore to the resolved content
                        logger.info("[Reject Action] Restoring content via VFS: $filePath (len=${restoreContent.length})")
                        if (!ioFile.exists()) {
                            ioFile.parentFile?.mkdirs()
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile.parentFile)
                        }
                        
                        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
                            ?: LocalFileSystem.getInstance().findFileByIoFile(ioFile.parentFile)?.createChildData(this, ioFile.name)
                            
                        if (virtualFile != null) {
                            VfsUtil.saveText(virtualFile, restoreContent)
                            result = true
                        }
                    } else if (!isTracked) {
                        // Untracked file with empty before = AI created this new file
                        logger.info("[Reject Action] Deleting untracked new file via VFS: $filePath")
                        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absPath)
                        if (virtualFile != null && virtualFile.exists()) {
                            virtualFile.delete(this)
                        }
                        result = true
                    } else {
                        // Tracked file with truly empty original (rare but valid)
                        logger.info("[Reject Action] Writing empty content to tracked file via VFS: $filePath")
                        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absPath)
                        if (virtualFile != null) {
                            VfsUtil.saveText(virtualFile, "")
                            result = true
                        }
                    }
                }
            }
            
            // Create label AFTER the operation so it marks the restored state
            if (result) {
                createLocalHistoryLabel("OpenCode Rejected $filePath")
                logger.info("[Reject] Successfully restored: $filePath")
            }
            result
        } catch (e: Exception) {
            logger.error("[Reject] VFS restore failed for $filePath", e)
            false
        }

        if (success) {
            diffsByFile.remove(filePath)
            removeFileFromBatches(filePath)
        }

        refreshFiles(listOf(filePath))
        return success
    }

    private fun showSessionCompletedNotification(sessionId: String) {
        val title = "OpenCode Task Completed"
        val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val content = "[$timeStr] Session $sessionId is now idle."
        
        // Ensure UI updates happen on EDT
        ApplicationManager.getApplication().invokeLater {
            // 1. IDE Notification (Balloon)
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")

            val notification = notificationGroup.createNotification(
                title,
                content,
                NotificationType.INFORMATION
            )

            notification.notify(project)
            
            // 2. System Notification (OS Level)
            // This ensures the user sees it even if the IDE is not focused.
            // Note: On macOS, this might require "System Preferences > Notifications" permission for the IDE.
            try {
                SystemNotifications.getInstance().notify("OpenCode", title, content)
            } catch (e: Throwable) {
                // Fallback or ignore if system notifications are not supported/allowed
                logger.debug("System notification failed", e)
            }
        }
    }

    /**
     * Create a project-level LocalHistory label.
     * Note: IntelliJ Platform only supports project-level labels, not file-level labels.
     * This allows users to recover from file modifications or destructive operations.
     */
    private fun createLocalHistoryLabel(label: String): Label? {
        return try {
            val app = ApplicationManager.getApplication()
            val createdLabel = if (app.isDispatchThread) {
                LocalHistory.getInstance().putSystemLabel(project, label)
            } else {
                var result: Label? = null
                app.invokeAndWait {
                    result = LocalHistory.getInstance().putSystemLabel(project, label)
                }
                result
            }
            logger.debug("Created LocalHistory label: $label")
            createdLabel
        } catch (e: Exception) {
            logger.warn("Failed to create LocalHistory label: $label", e)
            null
        }
    }

    private fun runGitCommand(args: List<String>, workDir: String): Boolean {
        try {
            val commandLine = GeneralCommandLine()
                .withExePath("git")
                .withWorkDirectory(workDir)
                .withParameters(args)
            
            val handler = OSProcessHandler(commandLine)
            val process = handler.process
            process.waitFor()
            return process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn("Git command failed: git ${args.joinToString(" ")}", e)
            return false
        }
    }

    // ========== Session Queries ==========

    /**
     * Get the currently active session ID.
     */
    fun getActiveSessionId(): String? = activeSessionId

    /**
     * Find the currently busy session from the server.
     */
    fun findBusySession(): SessionStatus? {
        val client = apiClient ?: return null
        val projectPath = project.basePath ?: return null
        return client.findBusySession(projectPath)
    }

    /**
     * Refresh and update active session from server.
     * Strategy:
     * 1. Check for BUSY session (currently working).
     * 2. If none, check for LATEST updated session (most recently used).
     */
    fun refreshActiveSession() {
        // 1. Try busy session
        val busySession = findBusySession()
        if (busySession != null) {
            updateActiveSession(busySession.sessionID)
            return
        }

        // 2. Try latest session
        val client = apiClient ?: return
        val projectPath = project.basePath ?: return
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sessions = client.getSessions(projectPath)
                val latestSession = sessions.maxByOrNull { it.time?.updated ?: 0.0 }
                
                if (latestSession != null) {
                    updateActiveSession(latestSession.id)
                }
            } catch (e: Exception) {
                logger.warn("Failed to refresh active session", e)
            }
        }
    }

    private fun updateActiveSession(sessionId: String) {
        if (activeSessionId != sessionId) {
            activeSessionId = sessionId
            logger.info("Active session updated: $sessionId")
        }
    }


    private fun refreshFiles(filePaths: Collection<String>) {
        if (filePaths.isEmpty()) return

        val localFs = LocalFileSystem.getInstance()
        val virtualFiles = filePaths.mapNotNull { filePath ->
            val absolutePath = toAbsolutePath(filePath) ?: return@mapNotNull null
            localFs.refreshAndFindFileByPath(absolutePath)
        }

        if (virtualFiles.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            virtualFiles.forEach { it.refresh(false, false) }
            FileDocumentManager.getInstance().reloadFiles(*virtualFiles.toTypedArray())
        }
    }

    private fun toAbsolutePath(filePath: String): String? {
        return PathUtil.resolveProjectPath(project, filePath)
    }

    // ========== Internal Helpers ==========

    private fun removeFileFromBatches(filePath: String) {
        diffBatchesBySession.forEach { (sessionId, batches) ->
            val updated = batches.mapNotNull { batch ->
                val remaining = batch.diffs.filterNot { it.file == filePath }
                if (remaining.isEmpty()) null else batch.copy(diffs = remaining)
            }

            if (updated.isEmpty()) {
                diffBatchesBySession.remove(sessionId)
            } else {
                diffBatchesBySession[sessionId] = updated.toMutableList()
            }
        }
    }

    // ========== Cleanup ==========

    /**
     * Clear diff caches only, keeping session state.
     * Called before processing new diffs to ensure each round only shows current changes.
     */
    fun clearDiffs() {
        diffsByFile.clear()
        diffBatchesBySession.clear()
    }

    /**
     * Clear all cached state.
     */
    fun clear() {
        clearDiffs()
        openCodeEditedFiles.clear()
        missingEditNoticeShown = false
        baselineLabel = null
        activeSessionId = null
        isCurrentlyBusy = false
    }

    override fun dispose() {
        logger.debug("Disposing SessionManager")
        clear()
        apiClient = null
    }
}

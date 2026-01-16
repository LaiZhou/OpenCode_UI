package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.*
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ui.SystemNotifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Paths
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

    // Latest message ID seen in this session
    private var lastMessageId: String? = null

    // Diff entries indexed by file path for quick lookup
    // Key: file path, Value: DiffEntry with full context (latest for that file)
    private val diffsByFile = ConcurrentHashMap<String, DiffEntry>()

    // All diff batches, indexed by sessionId
    // Key: sessionId, Value: list of DiffBatch from that session
    private val diffBatchesBySession = ConcurrentHashMap<String, MutableList<DiffBatch>>()

    // Track processed diff contents to filter out duplicates in subsequent rounds (Implicit Accept)
    // Key: FilePath, Value: AfterContent
    private val processedDiffs = ConcurrentHashMap<String, String>()

    // Snapshot of file content captured when session becomes busy (AI starts working)
    // Used ONLY for Reject fallback when server returns empty 'before' (server bug)
    // Key: FilePath, Value: Content at the moment session becomes busy
    private val baselineSnapshot = ConcurrentHashMap<String, String>()

    // Flag to track if the session is currently in a busy cycle
    // Used to capture baseline only once at the start of the busy cycle
    private var isCurrentlyBusy = false


    /**
     * Set the API client for server operations.
     */
    fun setApiClient(client: OpenCodeApiClient) {
        this.apiClient = client
    }

    /**
     * Get the latest message ID.
     */
    fun getLastMessageId(): String? = lastMessageId

    /**
     * Get the project path.
     */
    fun getProjectPath(): String? = project.basePath

    /**
     * Handle file edited event.
     * Refreshes the file in the IDE virtual file system.
     * Note: LocalHistory label is created at session.idle, not per-file edit.
     */
    fun onFileEdited(filePath: String) {
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
     * Filter out diffs that have not changed since the last round.
     * This implements "Implicit Accept": if a file was shown before and its content hasn't changed,
     * we don't show it again, even if the server returns it (because it's still modified relative to HEAD).
     */
    fun filterNewDiffs(diffs: List<FileDiff>): List<FileDiff> {
        logger.info("[Diff Filter] Server returned ${diffs.size} diffs, checking for duplicates...")
        
        val result = diffs.filter { diff ->
            val lastContent = processedDiffs[diff.file]
            val isFirstTime = lastContent == null
            val baseline = baselineSnapshot[diff.file]
            
            // Resolve effective 'after' content (handle server bug where chinese file content is empty)
            val effectiveAfter = resolveEffectiveContent(diff)
            
            // Detailed Debug Logging
            logger.info("[Diff Debug] Checking file: ${diff.file}")
            logger.info("  Server Before: len=${diff.before.length}")
            logger.info("  Server After:  len=${diff.after.length}")
            logger.info("  Baseline:      ${if (baseline != null) "Present (len=${baseline.length})" else "Missing (Null)"}")
            logger.info("  Last Content:  ${if (lastContent != null) "Present (len=${lastContent.length})" else "Missing (First Time)"}")
            logger.info("  Effect After:  len=${effectiveAfter.length}")
            
            // 1. AI changed its mind (new content generated)
            val aiChangedMind = lastContent != effectiveAfter
            
            // 2. Baseline context changed (User modified file)
            // If the starting point (baseline) has changed since last round, 
            // the diff context is new (e.g. C->B vs A->B), so we should show it
            // even if AI's target content (B) is the same.
            val contextChanged = baseline != null && lastContent != null && baseline != lastContent
            
            val isNew = isFirstTime || aiChangedMind || contextChanged
            
            if (!isNew) {
                logger.info("[Diff Filter] DECISION: SKIP (implicit accept)")
                logger.info("  Reason: aiChangedMind=$aiChangedMind, contextChanged=$contextChanged")
            } else {
                val reason = when {
                    isFirstTime -> "first time"
                    aiChangedMind -> "AI changed content"
                    contextChanged -> "baseline context changed"
                    else -> "unknown"
                }
                logger.info("[Diff Filter] DECISION: SHOW ($reason)")
            }
            isNew
        }
        
        logger.info("[Diff Filter] Result: ${result.size} new diffs out of ${diffs.size} total")
        return result
    }

    /**
     * Update the record of processed diffs.
     * Should be called after filtering and processing.
     */
    fun updateProcessedDiffs(diffs: List<FileDiff>) {
        diffs.forEach { diff ->
            val content = resolveEffectiveContent(diff)
            processedDiffs[diff.file] = content
            logger.info("[Diff State] Updated: ${diff.file} (after.length=${content.length})")
        }
    }
    
    private fun resolveEffectiveContent(diff: FileDiff): String {
        // If after is empty but additions > 0, it's likely the server bug for non-ASCII filenames
        if (diff.after.isEmpty() && diff.additions > 0) {
            val absPath = toAbsolutePath(diff.file)
            if (absPath != null) {
                val file = java.io.File(absPath)
                if (file.exists() && file.isFile) {
                    val content = readFileContent(file)
                    if (content.isNotEmpty()) {
                        logger.info("[Diff Resolve] Server 'after' empty. Loaded from disk: ${diff.file} (len=${content.length})")
                        return content
                    }
                }
            }
        }
        return diff.after
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
            
            // Only capture snapshot at the START of a busy cycle
            // This prevents overwriting the snapshot with intermediate AI changes
            // if server sends multiple 'busy' events during generation
            if (!isCurrentlyBusy) {
                isCurrentlyBusy = true
                // Capture baseline snapshot BEFORE AI modifies files
                // This is used as fallback when server returns empty 'before' (server bug)
                captureBaselineSnapshot()
                // Create LocalHistory label for user recovery
                createLocalHistoryLabel("OpenCode Modified Before")
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
     * Capture current content of all dirty files as baseline.
     * Called when session becomes busy (AI starts working).
     * 
     * IMPORTANT: This must complete BEFORE AI starts modifying files,
     * so it runs synchronously (blocking) to ensure we capture the true "before" state.
     */
    private fun captureBaselineSnapshot() {
        val projectPath = project.basePath ?: return
        
        // Use -c core.quotepath=false to ensure Chinese filenames are output as raw UTF-8, not octal escaped sequences
        val gitConfig = listOf("-c", "core.quotepath=false")
        
        val untracked = runGitCommandAndGetOutput(gitConfig + listOf("ls-files", "--others", "--exclude-standard"), projectPath)
        val modified = runGitCommandAndGetOutput(gitConfig + listOf("diff", "--name-only"), projectPath)
        val staged = runGitCommandAndGetOutput(gitConfig + listOf("diff", "--name-only", "--cached"), projectPath)
        
        val allFiles = (untracked + modified + staged).distinct()
        
        baselineSnapshot.clear()
        var count = 0
        allFiles.forEach { filePath ->
            if (filePath.isNotBlank()) {
                val absPath = toAbsolutePath(filePath)
                if (absPath != null) {
                    try {
                        val file = java.io.File(absPath)
                        if (file.exists() && file.isFile) {
                            baselineSnapshot[filePath] = readFileContent(file)
                            count++
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to capture baseline for: $filePath")
                    }
                }
            }
        }
        logger.info("Captured baseline snapshot: $count dirty files")
    }

    /**
     * Resolve the "before" content for a file.
     * 
     * This is the SINGLE SOURCE OF TRUTH used by both:
     * - DiffViewerService (to display the left pane)
     * - rejectDiff() (to restore the file)
     * 
     * Priority:
     * 1. Server returned 'before' (normal case)
     * 2. Baseline snapshot (captured when session became busy - handles unstaged/chinese bug)
     * 3. Git HEAD (final fallback for tracked files)
     * 4. Empty string (new untracked file)
     */
    fun resolveBeforeContent(filePath: String, serverBefore: String): String {
        // 1. Server provided content - trust it
        if (serverBefore.isNotEmpty()) {
            logger.info("[ResolveBefore] Using Server Content: $filePath (len=${serverBefore.length})")
            return serverBefore
        }
        
        // 2. Use baseline snapshot (captured before AI started)
        val baseline = baselineSnapshot[filePath]
        if (baseline != null) {
            logger.info("[ResolveBefore] Using Baseline Snapshot: $filePath (len=${baseline.length})")
            return baseline
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


    fun getProcessedContent(filePath: String): String? {
        return processedDiffs[filePath]
    }

    // ========== Accept/Reject Operations ==========

    /**
     * Accept a single file diff (git add).
     * 
     * Strategy:
     * - Stage the file using git add
     * - This is a non-destructive operation (user can unstage later)
     * - No LocalHistory needed since file content doesn't change
     * 
     * @return true if successful, false otherwise
     */
    fun acceptDiff(filePath: String): Boolean {
        val projectPath = project.basePath ?: return false
        
        val entry = diffsByFile[filePath]
        if (entry == null) {
            logger.warn("No diff entry found for accept: $filePath")
            return false
        }
        
        // Execute git add (no LocalHistory needed - file content doesn't change)
        val success = runGitCommand(listOf("add", filePath), projectPath)
        
        if (success) {
            diffsByFile.remove(filePath)
            removeFileFromBatches(filePath)
            logger.info("Accepted diff for file: $filePath (git add)")
        } else {
            logger.warn("Failed to accept diff for file: $filePath")
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
        
        val file = java.io.File(absPath)
        val isTracked = runGitCommand(listOf("ls-files", "--error-unmatch", filePath), projectPath)
        
        // Use the SAME logic as Diff display - what user sees is what they get
        val restoreContent = resolveBeforeContent(filePath, entry.diff.before)
        
        logger.info("[Reject Debug] Processing reject for: $filePath")
        logger.info("  Restore Content Len: ${restoreContent.length}")
        logger.info("  File Exists: ${file.exists()}")
        logger.info("  Git Tracked: $isTracked")
        
        val success = try {
            val result = if (restoreContent.isNotEmpty()) {
                // Normal case: restore to the resolved content
                logger.info("[Reject Action] Restoring content to disk (len=${restoreContent.length})")
                file.writeText(restoreContent)
                true
            } else if (!isTracked) {
                // Untracked file with empty before = AI created this new file
                if (entry.diff.deletions > 0) {
                    // Safety: file had content but we lost it - refuse to delete
                    logger.warn("[Reject Action] ABORTED: Untracked file has empty before but deletions > 0. Possible data loss risk.")
                    false
                } else {
                    logger.info("[Reject Action] Deleting untracked new file")
                    if (file.exists()) file.delete() else true
                }
            } else {
                // Tracked file with truly empty original (rare but valid)
                logger.info("[Reject Action] Writing empty content to tracked file")
                file.writeText("")
                true
            }
            
            // Create label AFTER the operation so it marks the restored state
            if (result) {
                createLocalHistoryLabel("OpenCode Rejected $filePath")
            }
            result
        } catch (e: Exception) {
            logger.warn("[Reject] Failed to reject diff for file: $filePath", e)
            false
        }

        if (success) {
            diffsByFile.remove(filePath)
            removeFileFromBatches(filePath)
            logger.info("Successfully rejected: $filePath")
        }

        refreshFiles(listOf(filePath))
        return success
    }
    
    private fun showSessionCompletedNotification(sessionId: String) {
        val title = "OpenCode Task Completed"
        val content = "Session $sessionId is now idle."
        
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
    private fun createLocalHistoryLabel(label: String) {
        try {
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread) {
                LocalHistory.getInstance().putSystemLabel(project, label)
            } else {
                app.invokeAndWait {
                    LocalHistory.getInstance().putSystemLabel(project, label)
                }
            }
            logger.debug("Created LocalHistory label: $label")
        } catch (e: Exception) {
            logger.warn("Failed to create LocalHistory label: $label", e)
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

    private fun runGitCommandAndGetOutput(args: List<String>, workDir: String): List<String> {
        return try {
            val commandLine = GeneralCommandLine()
                .withExePath("git")
                .withWorkDirectory(workDir)
                .withParameters(args)
            
            val handler = OSProcessHandler(commandLine)
            val output = handler.process.inputStream.bufferedReader().readLines()
            handler.process.waitFor()
            if (handler.process.exitValue() == 0) output else emptyList()
        } catch (e: Exception) {
            logger.warn("Git command failed (output): git ${args.joinToString(" ")}", e)
            emptyList()
        }
    }

    /**
     * Initialize the baseline of processed diffs.
     * This scans current untracked/modified files and records their content.
     * This prevents the plugin from showing existing changes as "New Diffs" in the first round.
     */
    fun initializeBaseline() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val projectPath = project.basePath ?: return@executeOnPooledThread
            logger.info("Initializing diff baseline...")
            
            // Use -c core.quotepath=false to ensure Chinese filenames are output as raw UTF-8
            val gitConfig = listOf("-c", "core.quotepath=false")
            
            // 1. Get untracked files
            val untracked = runGitCommandAndGetOutput(gitConfig + listOf("ls-files", "--others", "--exclude-standard"), projectPath)
            
            // 2. Get modified files (unstaged)
            val modified = runGitCommandAndGetOutput(gitConfig + listOf("diff", "--name-only"), projectPath)
            
            // 3. Get staged files
            val staged = runGitCommandAndGetOutput(gitConfig + listOf("diff", "--name-only", "--cached"), projectPath)
            
            val allFiles = (untracked + modified + staged).distinct()
            
            var count = 0
            allFiles.forEach { filePath ->
                if (filePath.isNotBlank()) {
                    val absPath = toAbsolutePath(filePath)
                    if (absPath != null) {
                        try {
                            val file = java.io.File(absPath)
                            if (file.exists() && file.isFile) {
                                // Record current content as "processed"
                                processedDiffs[filePath] = readFileContent(file)
                                count++
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to read baseline file: $filePath")
                        }
                    }
                }
            }
            logger.info("Initialized diff baseline: $count files recorded")
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
        var raw = filePath.trim()
        if (raw.isEmpty()) return null
        
        // Remove surrounding quotes if present (Git output for paths with spaces/special chars)
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length - 1)
        }

        val basePath = project.basePath
        return try {
            val path = Paths.get(raw)
            val resolved = when {
                path.isAbsolute -> path
                basePath != null -> Paths.get(basePath).resolve(path)
                else -> return null
            }
            resolved.normalize().toString()
        } catch (_: Exception) {
            null
        }
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
     * Unprocessed old diffs are treated as "implicitly accepted" (files already modified).
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
        processedDiffs.clear()
        baselineSnapshot.clear()
        activeSessionId = null
        isCurrentlyBusy = false
    }

    override fun dispose() {
        logger.debug("Disposing SessionManager")
        clear()
        apiClient = null
    }
}

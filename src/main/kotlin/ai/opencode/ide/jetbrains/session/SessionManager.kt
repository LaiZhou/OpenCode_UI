package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.*
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ApplicationManager
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
class SessionManager(private val project: Project) {

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
            createLocalHistoryLabel("OpenCode")
            logger.info("Created LocalHistory label: OpenCode (modified $fileCount files)")
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
            
            // Resolve effective 'after' content (handle server bug where chinese file content is empty)
            val effectiveAfter = resolveEffectiveContent(diff)
            
            val contentChanged = lastContent != effectiveAfter
            val isNew = isFirstTime || contentChanged
            
            if (!isNew) {
                logger.info("[Diff Filter] SKIP: ${diff.file} (content unchanged, implicit accept)")
            } else if (isFirstTime) {
                logger.info("[Diff Filter] NEW: ${diff.file} (first time seeing this file)")
            } else {
                logger.info("[Diff Filter] CHANGED: ${diff.file} (content differs from last time)")
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
                try {
                    val file = java.io.File(absPath)
                    if (file.exists() && file.isFile) {
                        return file.readText()
                    }
                } catch (_: Exception) { }
            }
        }
        return diff.after
    }

    /**
     * Handle session status change.
     */
    fun onSessionStatusChanged(sessionId: String, status: SessionStatusType) {
        logger.info("Session status changed: $sessionId -> ${status.type}")

        if (status.isBusy()) {
            activeSessionId = sessionId
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
     * Strategy:
     * - Use diff.before content to restore file (NOT git restore to HEAD)
     * - If before is empty and file is untracked, delete it (new file)
     * - Tracked empty files are restored to empty content
     * - If before has content, write it back to restore original state
     * - This preserves user's unstaged changes and staging state
     * 
     * See docs/diff_feature_plan.md for all scenarios.
     */
    fun rejectDiff(filePath: String): Boolean {
        val projectPath = project.basePath ?: return false
        
        // Get the DiffEntry to access before content
        val entry = diffsByFile[filePath]
        if (entry == null) {
            logger.warn("No diff entry found for file: $filePath")
            return false
        }
        
        val beforeContent = entry.diff.before
        val absPath = toAbsolutePath(filePath)
        if (absPath == null) {
            logger.warn("Failed to resolve absolute path for: $filePath")
            return false
        }
        
        val file = java.io.File(absPath)
        val isTracked = runGitCommand(listOf("ls-files", "--error-unmatch", filePath), projectPath)
        
        // LocalHistory Protection: Add label before any destructive operation
        createLocalHistoryLabel("OpenCode: Before rejecting $filePath")
        
        val success = try {
            if (beforeContent.isEmpty() && !isTracked) {
                // Case A: before is empty and file is untracked = OpenCode created this file -> delete it
                logger.info("Rejecting new untracked file (before is empty): $filePath")
                if (file.exists()) file.delete() else true
            } else {
                // Case B-F: restore to original content (including tracked empty files)
                // This only modifies worktree, preserving staging state
                logger.info("Rejecting modified file (restoring before content): $filePath")
                file.writeText(beforeContent)
                true
            }
        } catch (e: Exception) {
            logger.warn("Failed to reject diff for file: $filePath", e)
            false
        }

        if (success) {
            diffsByFile.remove(filePath)
            removeFileFromBatches(filePath)
            logger.info("Successfully rejected diff for file: $filePath (wasNewFile=${beforeContent.isEmpty() && !isTracked})")
        } else {
            logger.warn("Failed to reject diff for file: $filePath")
        }

        refreshFiles(listOf(filePath))
        return success
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
            
            // 1. Get untracked files
            val untracked = runGitCommandAndGetOutput(listOf("ls-files", "--others", "--exclude-standard"), projectPath)
            
            // 2. Get modified files (unstaged)
            val modified = runGitCommandAndGetOutput(listOf("diff", "--name-only"), projectPath)
            
            // 3. Get staged files
            val staged = runGitCommandAndGetOutput(listOf("diff", "--name-only", "--cached"), projectPath)
            
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
                                processedDiffs[filePath] = file.readText()
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
        val raw = filePath.trim()
        if (raw.isEmpty()) return null

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
        activeSessionId = null
    }
}

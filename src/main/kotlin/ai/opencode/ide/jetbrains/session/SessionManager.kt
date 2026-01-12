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
     */
    fun onFileEdited(filePath: String) {
        refreshFiles(listOf(filePath))
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
            .sortedWith(compareByDescending<DiffEntry> { it.timestamp }.thenBy { it.diff.file })
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
     */
    fun acceptDiff(filePath: String) {
        val projectPath = project.basePath ?: return
        
        // Execute git add
        runGitCommand(listOf("add", filePath), projectPath)
        
        val entry = diffsByFile.remove(filePath)
        if (entry != null) {
            removeFileFromBatches(filePath)
            logger.info("Accepted diff for file: $filePath (git add)")
        }
        refreshFiles(listOf(filePath))
    }


    /**
     * Reject a single file diff (git restore or rm).
     */
    fun rejectDiff(filePath: String): Boolean {
        val projectPath = project.basePath ?: return false

        // LocalHistory Protection: Add a label before discarding changes
        LocalHistory.getInstance().putSystemLabel(project, "OpenCode: Rejecting $filePath")
        
        // Check if untracked
        val isUntracked = !runGitCommand(listOf("ls-files", "--error-unmatch", filePath), projectPath)
        
        val success = if (isUntracked) {
            // Delete file if it's untracked
            try {
                val absPath = toAbsolutePath(filePath)
                if (absPath != null) {
                    val file = java.io.File(absPath)
                    if (file.exists()) file.delete() else true
                } else false
            } catch (e: Exception) {
                logger.warn("Failed to delete file: $filePath", e)
                false
            }
        } else {
            // Git restore for tracked files
            // Use --source=HEAD --staged --worktree to ensure full revert to HEAD
            runGitCommand(listOf("restore", "--source=HEAD", "--staged", "--worktree", filePath), projectPath)
        }

        if (success) {
            val entry = diffsByFile.remove(filePath)
            if (entry != null) {
                removeFileFromBatches(filePath)
            }
            logger.info("Rejected diff for file: $filePath (untracked=$isUntracked)")
        } else {
            logger.warn("Failed to reject diff for file: $filePath")
        }

        refreshFiles(listOf(filePath))
        return success
    }

    private fun runGitCommand(args: List<String>, workDir: String): Boolean {
        try {
            val commandLine = GeneralCommandLine()
                .withExePath("git")
                .withWorkDirectory(workDir)
                .withParameters(args)
            
            val handler = OSProcessHandler(commandLine)
            val process = handler.process
            // handler.startNotify() // Optional if we just want to wait
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
     * Clear all cached state.
     */
    fun clear() {
        diffsByFile.clear()
        diffBatchesBySession.clear()
        activeSessionId = null
    }
}

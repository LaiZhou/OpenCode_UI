package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileCopyEvent
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileMoveEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the diff state for a single AI "turn" (busy -> idle cycle).
 *
 * Design principles:
 * 1. Each turn is isolated by turnNumber
 * 2. State is snapshotted at turn end to avoid race conditions
 * 3. LocalHistory for reliable before-state restoration
 */
@Service(Service.Level.PROJECT)
open class SessionManager(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SessionManager::class.java)

    // ==================== Turn State ====================
    
    /** Monotonically increasing turn counter for isolation */
    @Volatile
    private var turnNumber = 0
    
    /** True while AI is actively working (between busy and idle) */
    @Volatile
    private var isBusy = false
    
    /** LocalHistory label created at turn start, used for reliable restore */
    private var baselineLabel: Label? = null
    
    /** Files edited by OpenCode during this turn (relative paths) */
    private var aiEditedFiles = ConcurrentHashMap.newKeySet<String>()
    
    /** Files created by OpenCode during this turn (relative paths) */
    private var aiCreatedFiles = ConcurrentHashMap.newKeySet<String>()
    
    /** Files edited by user during this turn (relative paths) */
    private val userEditedFiles = ConcurrentHashMap.newKeySet<String>()
    
    // ==================== Pending Diffs ====================
    
    /** Pending diffs awaiting user action, keyed by relative file path */
    private val pendingDiffs = ConcurrentHashMap<String, DiffEntry>()

    // ==================== Document Listener ====================
    
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!isBusy) return
            val cmd = CommandProcessor.getInstance().currentCommandName
            if (!cmd.isNullOrEmpty()) {
                FileDocumentManager.getInstance().getFile(event.document)?.let {
                    val relativePath = PathUtil.relativizeToProject(project, it.path)
                    userEditedFiles.add(relativePath)
                    logger.debug("[Turn #$turnNumber] User edit: $relativePath")
                }
            }
        }
    }

    private val vfsListener = object : VirtualFileListener {
        override fun fileCreated(event: VirtualFileEvent) {
            onVfsChange(event.file)
            // Always record creation to current set (supports late retry)
            val relPath = PathUtil.relativizeToProject(project, event.file.path)
            aiCreatedFiles.add(relPath)
        }
        override fun fileDeleted(event: VirtualFileEvent) = onVfsChange(event.file)
        override fun contentsChanged(event: VirtualFileEvent) = onVfsChange(event.file)
        override fun fileMoved(event: VirtualFileMoveEvent) = onVfsChange(event.file)
        // fileCopied is rare for AI and caused compilation issues
    }

    private fun onVfsChange(file: VirtualFile?) {
        if (file == null) return
        val relativePath = PathUtil.relativizeToProject(project, file.path)
        // Always record change to the current set (which represents the 'active' or 'just finished' turn)
        // This captures late events that arrive after Turn End but before Next Turn Start.
        if (aiEditedFiles.add(relativePath)) {
            logger.info("[Turn #$turnNumber] Detected VFS change: $relativePath")
        }
    }

    init {
        // Skip listener registration in test mode if Application is not loaded
        if (ApplicationManager.getApplication() != null) {
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
            VirtualFileManager.getInstance().addVirtualFileListener(vfsListener, this)
        }
    }

    // ==================== Test Hooks ====================
    
    protected open fun createSystemLabel(name: String): Label? {
        return try {
            LocalHistory.getInstance().putSystemLabel(project, name)
        } catch (e: Exception) {
            // Fallback for tests or when LocalHistory is unavailable
            null
        }
    }

    // ==================== Turn Lifecycle ====================

    /**
     * Called when session becomes busy. Starts a new turn.
     * @return true if a new turn was started (state was reset)
     */
    fun onTurnStart(): Boolean {
        if (isBusy) {
            logger.debug("[Turn #$turnNumber] onTurnStart: already busy, ignored")
            return false
        }
        
        val prevTurn = turnNumber
        val prevAiEdited = aiEditedFiles.toSet()
        val prevUserEdited = userEditedFiles.toSet()
        val prevPending = pendingDiffs.keys.toSet()
        
        turnNumber++
        isBusy = true
        // Swap sets to isolate the new turn from the previous one
        aiEditedFiles = ConcurrentHashMap.newKeySet()
        aiCreatedFiles = ConcurrentHashMap.newKeySet()
        userEditedFiles.clear()
        // Note: pendingDiffs is NOT cleared here - user may still be reviewing previous diffs
        baselineLabel = createSystemLabel("OpenCode Turn #$turnNumber Start")
        
        logger.info("╔══════════════════════════════════════════════════════════════")
        logger.info("║ [Turn #$turnNumber] STARTED")
        logger.info("║ Previous turn #$prevTurn state:")
        logger.info("║   aiEditedFiles: ${if (prevAiEdited.isEmpty()) "(empty)" else prevAiEdited}")
        logger.info("║   userEditedFiles: ${if (prevUserEdited.isEmpty()) "(empty)" else prevUserEdited}")
        logger.info("║   pendingDiffs: ${if (prevPending.isEmpty()) "(empty)" else prevPending}")
        logger.info("╚══════════════════════════════════════════════════════════════")
        return true
    }

    /**
     * Called when AI finishes working. Returns a snapshot of this turn's state.
     * @return TurnSnapshot containing all state needed for diff processing, or null if not busy
     */
    fun onTurnEnd(): TurnSnapshot? {
        if (!isBusy) {
            logger.warn("[Turn #$turnNumber] onTurnEnd: not busy, ignored")
            return null
        }
        
        isBusy = false
        
        // Create immutable snapshot of current turn state
        // IMPORTANT: We pass the LIVE references to aiEditedFiles/aiCreatedFiles sets.
        // This allows VFS events arriving during the "Grace Period" (Idle state) to be seen by the snapshot consumers.
        // Since onTurnStart() swaps these references, future turns won't affect this snapshot.
        val snapshot = TurnSnapshot(
            turnNumber = turnNumber,
            aiEditedFiles = aiEditedFiles, 
            aiCreatedFiles = aiCreatedFiles,
            userEditedFiles = userEditedFiles.toSet(),
            baselineLabel = baselineLabel
        )
        
        logger.info("╔══════════════════════════════════════════════════════════════")
        logger.info("║ [Turn #$turnNumber] ENDED")
        logger.info("║   aiEditedFiles: ${if (snapshot.aiEditedFiles.isEmpty()) "(empty)" else snapshot.aiEditedFiles}")
        logger.info("║   userEditedFiles: ${if (snapshot.userEditedFiles.isEmpty()) "(empty)" else snapshot.userEditedFiles}")
        logger.info("╚══════════════════════════════════════════════════════════════")
        
        return snapshot
    }

    /**
     * Called when OpenCode edits a file (from file.edited SSE event).
     */
    fun onFileEdited(filePath: String) {
        val relativePath = PathUtil.relativizeToProject(project, filePath)
        aiEditedFiles.add(relativePath)
        logger.info("[Turn #$turnNumber] AI edited: $relativePath (total: ${aiEditedFiles.size})")
    }

    // ==================== Diff Processing ====================

    /**
     * Process incoming diffs using a turn snapshot.
     * 
     * STRATEGY: Trust Server Diffs ("Server Authoritative").
     * 
     * We accept ALL diffs returned by the server because they represent the actual work done by the AI.
     * We do NOT filter by 'aiEditedFiles' (VFS events) because VFS events are asynchronous and may 
     * arrive AFTER the turn ends (Race Condition), especially for new files.
     * 
     * VFS events ('snapshot.aiEditedFiles', 'snapshot.userEditedFiles') are used ONLY for:
     * 1. Detecting user conflicts ('hasUserEdits')
     * 2. Identifying new files ('isCreatedExplicitly') for smarter Reject behavior
     * 
     * Trade-off: If VFS is very late, we might miss a "User Edited" warning or treat a New File 
     * as an "Edit from Empty". This is acceptable to guarantee the Diff is always shown.
     *
     * @param serverDiffs Diffs from server API
     * @param snapshot Snapshot from the turn that ended (from onTurnEnd)
     * @return List of entries ready for display
     */
    fun processDiffs(serverDiffs: List<FileDiff>, snapshot: TurnSnapshot): List<DiffEntry> {
        logger.info("[Turn #${snapshot.turnNumber}] Processing ${serverDiffs.size} server diffs")
        
        if (serverDiffs.isEmpty()) {
            logger.info("[Turn #${snapshot.turnNumber}] No server diffs, nothing to display")
            return emptyList()
        }
        
        val serverFiles = serverDiffs.map { it.file }.toSet()
        logger.info("[Turn #${snapshot.turnNumber}] Server files: $serverFiles")
        logger.info("[Turn #${snapshot.turnNumber}] VFS detected: ${snapshot.aiEditedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}] VFS created: ${snapshot.aiCreatedFiles}")
        
        // Trust ALL server diffs - they represent actual changes made by the AI
        // VFS events are used for additional context (user edits, new file detection) 
        // but NOT for filtering, as VFS events may arrive late for new files
        val entries = serverDiffs.map { diff ->
            DiffEntry(
                file = diff.file,
                diff = diff,
                hasUserEdits = diff.file in snapshot.userEditedFiles,
                isCreatedExplicitly = diff.file in snapshot.aiCreatedFiles
            )
        }
        
        // Store in pendingDiffs for accept/reject operations
        entries.forEach { pendingDiffs[it.file] = it }
        
        logger.info("[Turn #${snapshot.turnNumber}] Created ${entries.size} diff entries: ${entries.map { it.file }}")
        return entries
    }

    /**
     * Resolve the "before" content for a file using a snapshot's baseline.
     */
    fun resolveBeforeContent(relativePath: String, diff: FileDiff, snapshot: TurnSnapshot): String {
        val absPath = PathUtil.resolveProjectPath(project, relativePath)
        val serverBefore = diff.before

        // 1. Try LocalHistory (Baseline)
        if (snapshot.baselineLabel != null && absPath != null) {
            try {
                snapshot.baselineLabel.getByteContent(absPath)?.let { 
                    return String(it.bytes, Charsets.UTF_8) 
                }
            } catch (e: Exception) {
                logger.debug("[Turn #${snapshot.turnNumber}] LocalHistory lookup failed for $relativePath: ${e.message}")
            }
        }
        
        // 2. Fallback: If Server Before is empty but file exists on disk -> Read Disk
        // CRITICAL: Only do this if 'After' is also empty (Delete intent).
        // If 'After' has content (Create/Modify), disk likely contains AI's new content,
        // so reading it would incorrectly set Before == After, hiding the diff.
        if (serverBefore.isEmpty() && diff.after.isEmpty() && absPath != null) {
            try {
                val file = File(absPath)
                if (file.exists()) {
                    logger.info("[Turn #${snapshot.turnNumber}] Server before is empty but intent is Delete. Using disk content as before.")
                    return file.readText()
                }
            } catch (e: Exception) {
                logger.warn("[Turn #${snapshot.turnNumber}] Failed to read disk fallback for $relativePath", e)
            }
        }

        // 3. Default: Server Before
        return serverBefore
    }

    // ==================== Accept/Reject Operations ====================

    /**
     * Accept a diff: stage the file via Git.
     */
    fun acceptDiff(entry: DiffEntry, onComplete: ((Boolean) -> Unit)? = null) {
        val path = entry.file
        logger.info("[SessionManager] Accept: $path")

        AppExecutorUtil.getAppExecutorService().submit {
            var success = false
            try {
                val cmd = GeneralCommandLine("git", "add", path)
                    .withWorkDirectory(project.basePath)
                val handler = OSProcessHandler(cmd)
                handler.startNotify()
                val finished = handler.waitFor(5000)
                val exitCode = handler.exitCode ?: -1
                success = finished && exitCode == 0
                
                if (success) {
                    pendingDiffs.remove(path)
                    logger.info("[SessionManager] Accepted: $path")
                } else {
                    // Special case: File deleted and likely untracked (git add fails with 128)
                    // If the file is gone from disk, we can consider the "delete" accepted.
                    val absPath = PathUtil.resolveProjectPath(project, path)
                    if (exitCode == 128 && absPath != null && !File(absPath).exists()) {
                        logger.info("[SessionManager] Git add failed (128) but file is deleted. Treating as success.")
                        pendingDiffs.remove(path)
                        success = true // Mark as success for callback
                    } else {
                        logger.warn("[SessionManager] Git add failed: exitCode=$exitCode")
                    }
                }
            } catch (e: Exception) {
                logger.error("[SessionManager] Accept failed: $path", e)
            }

            onComplete?.let { cb ->
                ApplicationManager.getApplication().invokeLater { cb(success) }
            }
        }
    }

    /**
     * Reject a diff: restore file to its original state.
     */
    fun rejectDiff(entry: DiffEntry, onComplete: ((Boolean) -> Unit)? = null) {
        val path = entry.file
        val absPath = PathUtil.resolveProjectPath(project, path)
        
        if (absPath == null) {
            logger.warn("[SessionManager] Reject: cannot resolve path: $path")
            onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
            return
        }

        // Use the entry's resolved before content
        val beforeContent = entry.beforeContent
        logger.info("[SessionManager] Reject: $path (restore ${beforeContent.length} chars)")

        ApplicationManager.getApplication().invokeLater {
            var success = false
            runWriteAction {
                try {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
                    
                    when {
                        vf != null && entry.isNewFile -> {
                            vf.delete(this@SessionManager)
                            success = true
                            logger.info("[SessionManager] Rejected (deleted new file): $path")
                        }
                        vf != null -> {
                            VfsUtil.saveText(vf, beforeContent)
                            success = true
                            logger.info("[SessionManager] Rejected (restored): $path")
                        }
                        beforeContent.isNotEmpty() -> {
                            val file = File(absPath)
                            file.parentFile?.mkdirs()
                            file.writeText(beforeContent)
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                            success = true
                            logger.info("[SessionManager] Rejected (recreated): $path")
                        }
                        else -> {
                            success = true
                            logger.info("[SessionManager] Rejected (no-op): $path")
                        }
                    }

                    if (success) {
                        pendingDiffs.remove(path)
                        createSystemLabel("OpenCode Rejected: $path")
                    }
                } catch (e: Exception) {
                    logger.error("[SessionManager] Reject failed: $path", e)
                }
            }

            onComplete?.invoke(success)
        }
    }

    // ==================== Queries ====================

    fun getCurrentTurnNumber(): Int = turnNumber
    
    fun getDiffForFile(path: String): DiffEntry? {
        val relativePath = PathUtil.relativizeToProject(project, path)
        return pendingDiffs[relativePath]
    }

    fun getAllDiffEntries(): List<DiffEntry> = pendingDiffs.values.toList()

    fun hasPendingDiffs(): Boolean = pendingDiffs.isNotEmpty()

    // ==================== Lifecycle ====================

    fun clear() {
        isBusy = false
        baselineLabel = null
        aiEditedFiles.clear()
        aiCreatedFiles.clear()
        userEditedFiles.clear()
        pendingDiffs.clear()
    }

    override fun dispose() {
        clear()
    }
}

/**
 * Immutable snapshot of a turn's state, captured at turn end.
 * Used to ensure diff processing uses the correct turn's data even if a new turn starts.
 */
data class TurnSnapshot(
    val turnNumber: Int,
    val aiEditedFiles: Set<String>,
    val aiCreatedFiles: Set<String>,
    val userEditedFiles: Set<String>,
    val baselineLabel: Label?
)

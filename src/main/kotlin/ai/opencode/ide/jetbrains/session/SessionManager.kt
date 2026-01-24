package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.util.PathUtil
import java.io.File

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
    
    /** Files physically changed on VFS during this turn (AI + User) */
    private var vfsChangedFiles = ConcurrentHashMap.newKeySet<String>()
    
    /** Files explicitly reported by Server as edited (via SSE file.edited) */
    private var serverEditedFiles = ConcurrentHashMap.newKeySet<String>()
    
    /** Files created by OpenCode during this turn (relative paths) */
    private var aiCreatedFiles = ConcurrentHashMap.newKeySet<String>()
    
    /** Files edited by user during this turn (relative paths) */
    private val userEditedFiles = ConcurrentHashMap.newKeySet<String>()
    
    /**
     * Pre-emptively captured content of files BEFORE they are modified in this turn.
     * Captured via VFS `beforeContentsChange` and `beforeFileDeletion` events.
     * This provides the most reliable "Before" state, independent of Turn Start timing or LocalHistory latency.
     * Key: Relative Path, Value: File Content
     */
    private var capturedBeforeContent = ConcurrentHashMap<String, String>()
    
    /** 
     * Cache of last known file content after a Reject operation.
     * Used as a reliable fallback for 'Before' content if LocalHistory fails/is empty 
     * and Server state is stale (e.g. Server thinks file is deleted).
     */
    private val lastKnownFileStates = ConcurrentHashMap<String, String>()
    
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
                    logger.info("[Turn #$turnNumber] User edit detected: $relativePath")
                }
            }
        }
    }

    private val vfsListener = object : VirtualFileListener {
        override fun fileCreated(event: VirtualFileEvent) {
            onVfsChange(event.file)
            // Record creation event - will be cross-checked with serverEditedFiles later
            // to determine if it's truly AI-created or user-created
            val relPath = PathUtil.relativizeToProject(project, event.file.path)
            aiCreatedFiles.add(relPath)
            logger.info("[Turn #$turnNumber] VFS fileCreated: $relPath")
        }
        
        override fun beforeFileDeletion(event: VirtualFileEvent) {
            captureContentBeforeChange(event.file)
        }
        
        override fun beforeContentsChange(event: VirtualFileEvent) {
            captureContentBeforeChange(event.file)
        }
        
        override fun fileDeleted(event: VirtualFileEvent) = onVfsChange(event.file)
        override fun contentsChanged(event: VirtualFileEvent) = onVfsChange(event.file)
        override fun fileMoved(event: VirtualFileMoveEvent) = onVfsChange(event.file)
        // fileCopied is rare for AI and caused compilation issues
    }

    internal fun captureContentBeforeChange(file: VirtualFile?) {
        if (file == null || !file.isValid || file.isDirectory) return
        
        val relativePath = PathUtil.relativizeToProject(project, file.path)
        
        // Only capture if NOT already captured for this turn/gap cycle.
        // We want the ORIGINAL state before the first modification in this cycle.
        if (!capturedBeforeContent.containsKey(relativePath)) {
            try {
                // Try VFS first
                val content = VfsUtil.loadText(file)
                capturedBeforeContent.putIfAbsent(relativePath, content)
                logger.info("[Turn #$turnNumber] Captured before content for: $relativePath (${content.length} chars)")
            } catch (e: Exception) {
                logger.warn("[Turn #$turnNumber] VfsUtil load failed for $relativePath: ${e.message}. Trying java.io.File...")
                // Fallback to java.io.File
                try {
                    val ioFile = File(file.path)
                    if (ioFile.exists()) {
                        val content = ioFile.readText()
                        capturedBeforeContent.putIfAbsent(relativePath, content)
                        logger.info("[Turn #$turnNumber] Captured via java.io.File for: $relativePath")
                    } else {
                        logger.warn("[Turn #$turnNumber] File not found on disk: $relativePath. Trying LocalHistory...")
                        // Fallback to LocalHistory (Last known state provided by IDE)
                        // This handles external deletions where VFS event comes after physical deletion.
                        val bytes = LocalHistory.getInstance().getByteContent(file) { true }
                        if (bytes != null && bytes.isNotEmpty()) {
                            val content = String(bytes, Charsets.UTF_8)
                            capturedBeforeContent.putIfAbsent(relativePath, content)
                            logger.info("[Turn #$turnNumber] Captured via LocalHistory (Latest) for: $relativePath")
                        } else {
                            logger.warn("[Turn #$turnNumber] LocalHistory lookup returned null or empty for $relativePath")
                        }
                    }
                } catch (ioe: Exception) {
                    logger.error("[Turn #$turnNumber] Failed to capture content for $relativePath", ioe)
                }
            }
        }
    }

    private fun onVfsChange(file: VirtualFile?) {
        if (file == null) return
        val relativePath = PathUtil.relativizeToProject(project, file.path)
        // Always record change to the current set (which represents the 'active' or 'just finished' turn)
        // This captures late events that arrive after Turn End but before Next Turn Start.
        if (vfsChangedFiles.add(relativePath)) {
            logger.info("[Turn #$turnNumber] Detected VFS change: $relativePath")
        }
    }

    // ... init ...

    // ==================== Turn Lifecycle ====================

    /**
     * Called when session becomes busy. Starts a new turn.
     * @return true if a new turn was started (state was reset)
     */
    open fun onTurnStart(): Boolean {
        if (isBusy) {
            logger.info("[Turn #$turnNumber] onTurnStart: already busy, ignored")
            return false
        }
        
        val prevTurn = turnNumber
        val prevVfsChanged = vfsChangedFiles.toSet()
        val prevServerEdited = serverEditedFiles.toSet()
        val prevUserEdited = userEditedFiles.toSet()
        val prevPending = pendingDiffs.keys.toSet()
        
        turnNumber++
        isBusy = true
        
        // STRICT TURN ISOLATION
        // We clear ALL change sets at the start of a turn. 
        // This prevents "Gap Events" (e.g. late VFS events from the previous turn's refresh)
        // from polluting the new turn. We want a clean slate for the AI's new work.
        userEditedFiles.clear()
        vfsChangedFiles.clear()
        serverEditedFiles.clear()
        aiCreatedFiles.clear()
        capturedBeforeContent.clear()
        
        baselineLabel = createSystemLabel("OpenCode Turn #$turnNumber Start")
        
        logger.info("╔══════════════════════════════════════════════════════════════")
        logger.info("║ [Turn #$turnNumber] STARTED")
        logger.info("║ Previous turn #$prevTurn state:")
        logger.info("║   vfsChangedFiles: ${if (prevVfsChanged.isEmpty()) "(empty)" else prevVfsChanged}")
        logger.info("║   serverEditedFiles: ${if (prevServerEdited.isEmpty()) "(empty)" else prevServerEdited}")
        logger.info("║   userEditedFiles: ${if (prevUserEdited.isEmpty()) "(empty)" else prevUserEdited}")
        logger.info("║   pendingDiffs: ${if (prevPending.isEmpty()) "(empty)" else prevPending}")
        logger.info("╚══════════════════════════════════════════════════════════════")
        return true
    }

    /**
     * Called when AI finishes working. Returns a snapshot of this turn's state.
     * @return TurnSnapshot containing all state needed for diff processing, or null if not busy
     */
    open fun onTurnEnd(): TurnSnapshot? {
        if (!isBusy) {
            logger.warn("[Turn #$turnNumber] onTurnEnd: not busy, ignored")
            return null
        }
        
        isBusy = false
        
        // Update lastKnownFileStates with current content of modified files.
        // This serves as a memory snapshot for the NEXT turn to use as 'Before' state,
        // especially crucial if the file is deleted in the next turn and LocalHistory fails.
        val allModifiedFiles = vfsChangedFiles + serverEditedFiles + aiCreatedFiles
        allModifiedFiles.forEach { relativePath ->
            val absPath = PathUtil.resolveProjectPath(project, relativePath)
            if (absPath != null) {
                val file = File(absPath)
                if (file.exists() && file.isFile) {
                    try {
                        lastKnownFileStates[relativePath] = file.readText()
                        logger.info("[Turn #$turnNumber] Updated known state for $relativePath")
                    } catch (e: Exception) {
                        logger.warn("[Turn #$turnNumber] Failed to capture known state for $relativePath: ${e.message}")
                    }
                }
            }
        }
        
        // Create immutable snapshot of current turn state
        val snapshot = TurnSnapshot(
            turnNumber = turnNumber,
            vfsChangedFiles = vfsChangedFiles,
            serverEditedFiles = serverEditedFiles,
            aiCreatedFiles = aiCreatedFiles,
            userEditedFiles = userEditedFiles.toSet(),
            baselineLabel = baselineLabel,
            knownFileStates = HashMap(lastKnownFileStates),
            capturedBeforeContent = HashMap(capturedBeforeContent) // Snapshot the captured content
        )
        
        logger.info("╔══════════════════════════════════════════════════════════════")
        logger.info("║ [Turn #$turnNumber] ENDED")
        logger.info("║   vfsChangedFiles: ${if (snapshot.vfsChangedFiles.isEmpty()) "(empty)" else snapshot.vfsChangedFiles}")
        logger.info("║   serverEditedFiles: ${if (snapshot.serverEditedFiles.isEmpty()) "(empty)" else snapshot.serverEditedFiles}")
        logger.info("║   capturedBefore: ${snapshot.capturedBeforeContent.keys}")
        logger.info("╚══════════════════════════════════════════════════════════════")
        
        // Rotate AI sets for the next interval (Gap + Next Turn).
        vfsChangedFiles = ConcurrentHashMap.newKeySet()
        serverEditedFiles = ConcurrentHashMap.newKeySet()
        aiCreatedFiles = ConcurrentHashMap.newKeySet()
        capturedBeforeContent = ConcurrentHashMap() // Rotate capture map
        
        return snapshot
    }

    /**
     * Called when OpenCode edits a file (from file.edited SSE event).
     */
    fun onFileEdited(filePath: String) {
        val relativePath = PathUtil.relativizeToProject(project, filePath)
        serverEditedFiles.add(relativePath)
        logger.info("[Turn #$turnNumber] Server reported edit: $relativePath")
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



    // ==================== Diff Processing ====================

    /**
     * Main entry point for processing diffs.
     * Handles Rescue, Resolution, and Filtering in one place (Single Responsibility).
     * @param extraVfsEvents Additional VFS events (e.g. from post-turn refresh) to consider for affinity.
     */
    fun getProcessedDiffs(serverDiffs: List<FileDiff>, snapshot: TurnSnapshot, extraVfsEvents: Set<String> = emptySet()): List<DiffEntry> {
        logger.info("[Turn #${snapshot.turnNumber}] Processing diffs (Server: ${serverDiffs.size})")
        logger.info("[Turn #${snapshot.turnNumber}] Snapshot state:")
        logger.info("[Turn #${snapshot.turnNumber}]   vfsChangedFiles: ${snapshot.vfsChangedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   serverEditedFiles: ${snapshot.serverEditedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   aiCreatedFiles: ${snapshot.aiCreatedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   userEditedFiles: ${snapshot.userEditedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   capturedBeforeContent keys: ${snapshot.capturedBeforeContent.keys}")
        logger.info("[Turn #${snapshot.turnNumber}]   extraVfsEvents: $extraVfsEvents")

        // 1. VFS Rescue: Find files changed/deleted locally but missed by Server
        // Only rescue files that have STRONG AI affinity (serverEditedFiles) or physical deletion
        val rescueCandidates = snapshot.vfsChangedFiles + snapshot.capturedBeforeContent.keys + extraVfsEvents
        val serverFileNames = serverDiffs.map { it.file }.toSet()

        val vfsMissing = rescueCandidates.filter { vfsFile ->
            // Skip if Server already returned this file
            if (vfsFile in serverFileNames) {
                logger.info("[Rescue] $vfsFile -> SKIP: Already in server diffs")
                return@filter false
            }

            // Skip IDE internal files
            if (vfsFile.startsWith(".idea/") || vfsFile.contains("idea-sandbox")) {
                logger.info("[Rescue] $vfsFile -> SKIP: IDE internal file")
                return@filter false
            }

            // CRITICAL: Skip if user edited this file - user operations should NOT be rescued as AI diffs
            if (vfsFile in snapshot.userEditedFiles) {
                logger.info("[Rescue] $vfsFile -> SKIP: User edited this file")
                return@filter false
            }

            val absPath = PathUtil.resolveProjectPath(project, vfsFile) ?: return@filter false
            val exists = File(absPath).exists()

            // Determine if this is truly an AI operation by checking various signals
            val isServerClaimed = vfsFile in snapshot.serverEditedFiles
            val isVfsCreated = vfsFile in snapshot.aiCreatedFiles
            // Check if we can resolve the before content (from Capture, LocalHistory, or KnownState)
            val hasCapturedBefore = getSnapshotBeforeContent(vfsFile, snapshot) != null

            // Rescue conditions (conservative, requires evidence of AI involvement):
            //
            // For DELETION (!exists):
            //   - If we have capturedBeforeContent, we can safely show the diff
            //     (we know what the original content was, so Reject can restore it)
            //   - OR if Server claimed it via file.edited SSE event
            //
            // For CREATION (exists && isVfsCreated):
            //   - MUST have Server claim (file.edited SSE) to distinguish from user-created files
            //   - VFS alone cannot tell us if AI or User created the file
            //
            val shouldRescue = when {
                // Case 1: Deletion with captured content - safe to rescue
                !exists && hasCapturedBefore -> {
                    logger.info("[Rescue] $vfsFile -> RESCUE: Deleted + Has captured content (${snapshot.capturedBeforeContent[vfsFile]?.length} chars)")
                    true
                }
                // Case 2: Deletion with Server claim - safe to rescue
                !exists && isServerClaimed -> {
                    logger.info("[Rescue] $vfsFile -> RESCUE: Deleted + Server claimed")
                    true
                }
                // Case 3: Creation with BOTH VFS and Server claim - safe to rescue
                exists && isVfsCreated && isServerClaimed -> {
                    logger.info("[Rescue] $vfsFile -> RESCUE: Created (VFS) + Server claimed")
                    true
                }
                // Default: Not enough evidence, skip
                else -> {
                    logger.info("[Rescue] $vfsFile -> SKIP: Insufficient AI affinity (exists=$exists, serverClaimed=$isServerClaimed, vfsCreated=$isVfsCreated, hasCaptured=$hasCapturedBefore)")
                    false
                }
            }
            shouldRescue
        }.toSet()

        logger.info("[Turn #${snapshot.turnNumber}] Rescue candidates: $vfsMissing")

        val allDiffs = serverDiffs + vfsMissing.mapNotNull { createSyntheticDiff(it, snapshot) }
        
        if (allDiffs.isEmpty()) {
            logger.info("[Turn #${snapshot.turnNumber}] No diffs after rescue.")
            return emptyList()
        }

        // 2. Resolve Content & Create Entries
        val entries = allDiffs.map { diff ->
            val resolvedBefore = resolveBeforeContent(diff.file, diff, snapshot)
            DiffEntry(
                file = diff.file,
                diff = diff,
                hasUserEdits = diff.file in snapshot.userEditedFiles,
                isCreatedExplicitly = diff.file in snapshot.aiCreatedFiles,
                resolvedBefore = resolvedBefore
            )
        }

        // 3. Filtering (Safety, Content & Server Consistency)
        val filteredEntries = entries.filter { entry ->
            val file = entry.file
            val before = entry.beforeContent
            val after = entry.diff.after

            // A. User Safety (Highest Priority)
            // If the user edited the file during this turn, we MUST NOT show the AI's diff
            // to avoid overwriting user work or showing confusing conflict states.
            if (entry.hasUserEdits) {
                logger.info("[DiffFilter] $file -> SKIP: User edited this file.")
                return@filter false
            }

            // B. Server Consistency Check (Prevent Stale Diffs)
            // Server API may return stale/cached diffs from previous turns.
            // CRITICAL RULE: Diffs from Server API MUST have corresponding SSE file.edited event OR be a VFS Rescue.
            //
            // Why: serverEditedFiles contains real-time SSE events for THIS turn.
            // If Server API returns a diff without SSE claim, it's stale data from a previous turn.
            //
            // Exception: Rescued diffs (Synthetic Diffs) which we generated locally.
            // We can identify these by checking if the file is NOT in the original serverDiffs list.
            // BUT: Ideally we should just check if it was touched by VFS or Server in THIS turn.
            
            // Check for "Signal Affinity": Did this file have any activity in this turn?
            val isServerClaimed = file in snapshot.serverEditedFiles
            val isVfsTouched = file in snapshot.vfsChangedFiles || 
                              file in snapshot.aiCreatedFiles || 
                              file in snapshot.capturedBeforeContent.keys ||
                              file in extraVfsEvents
            
            if (!isServerClaimed && !isVfsTouched) {
                logger.info("[DiffFilter] $file -> SKIP: Stale API diff (No SSE or VFS signal in this turn).")
                return@filter false
            }

            // C. Content Filter (Ghost Diffs)
            // If the resolved content is identical, there's nothing to accept/reject.
            // This filters out "noise" where VFS triggered but content didn't actually change,
            // or where Server sent a diff that matches current state.
            if (before == after) {
                logger.info("[DiffFilter] $file -> SKIP: Identical content (len=${before.length}).")
                return@filter false
            }

            logger.info("[DiffFilter] $file -> SHOW: Changed (B:${before.length} -> A:${after.length}, serverClaimed=$isServerClaimed, vfsTouched=$isVfsTouched)")
            true
        }

        // 4. Store Pending
        filteredEntries.forEach { pendingDiffs[it.file] = it }
        
        return filteredEntries
    }

    /**
     * Create a synthetic FileDiff for a file that VFS detected but Server didn't return.
     * This is the "Rescue" mechanism for files that the Server API missed.
     */
    private fun createSyntheticDiff(relativePath: String, snapshot: TurnSnapshot): FileDiff? {
        logger.info("[SyntheticDiff] Creating for: $relativePath")

        val absPath = PathUtil.resolveProjectPath(project, relativePath)
        if (absPath == null) {
            logger.info("[SyntheticDiff] $relativePath -> SKIP: Cannot resolve path")
            return null
        }

        val file = File(absPath)
        val fileExists = file.exists()

        val afterContent = if (fileExists) {
            try {
                file.readText()
            } catch (e: Exception) {
                logger.warn("[SyntheticDiff] $relativePath -> Failed to read file: ${e.message}")
                ""
            }
        } else {
            "" // File deleted - after content is empty
        }

        var beforeContent = getSnapshotBeforeContent(relativePath, snapshot)
        logger.info("[SyntheticDiff] $relativePath -> beforeContent from snapshot: ${beforeContent?.let { "${it.length} chars" } ?: "null"}")

        if (beforeContent == null) {
            // Determine what to do based on the scenario
            val isServerClaimed = relativePath in snapshot.serverEditedFiles
            val isVfsCreated = relativePath in snapshot.aiCreatedFiles

            when {
                // New file created by AI (VFS + Server confirm): before should be empty
                isVfsCreated && isServerClaimed -> {
                    logger.info("[SyntheticDiff] $relativePath -> New file (VFS + Server), before = empty")
                    beforeContent = ""
                }
                // File deleted but no captured content: we CANNOT rescue safely
                // because we don't know what the original content was.
                // NOTE: This case should NOT happen if Rescue logic is correct,
                // because we only rescue deletions when we HAVE captured content.
                !fileExists -> {
                    logger.warn("[SyntheticDiff] $relativePath -> SKIP: Deleted but no captured before content (this should not happen)")
                    return null
                }
                // File exists but we don't know the before state: skip to be safe
                else -> {
                    logger.info("[SyntheticDiff] $relativePath -> SKIP: Unknown before state (exists=$fileExists, serverClaimed=$isServerClaimed, vfsCreated=$isVfsCreated)")
                    return null
                }
            }
        }

        val finalBefore = beforeContent!!

        // Final sanity check: if before == after, no point creating this diff
        if (finalBefore == afterContent) {
            logger.info("[SyntheticDiff] $relativePath -> SKIP: before == after (no change)")
            return null
        }

        logger.info("[SyntheticDiff] $relativePath -> CREATED: before=${finalBefore.length} chars, after=${afterContent.length} chars")
        return FileDiff(
            relativePath,
            finalBefore,
            afterContent,
            if (afterContent.isNotEmpty()) afterContent.lines().size else 0,
            if (finalBefore.isNotEmpty()) finalBefore.lines().size else 0
        )
    }

    /**
     * Process incoming diffs using a turn snapshot.
     * DEPRECATED: Use getProcessedDiffs instead. Keeping for now if needed by tests, but ideally remove.
     */
    fun processDiffs(serverDiffs: List<FileDiff>, snapshot: TurnSnapshot): List<DiffEntry> {
        return getProcessedDiffs(serverDiffs, snapshot)
    }

    /**
     * Try to retrieve the "Before" content from the snapshot using all available sources.
     * Order:
     * 1. Pre-emptively Captured Content (Physical)
     * 2. LocalHistory Baseline (Time-based)
     * 3. Known State (Memory)
     */
    fun getSnapshotBeforeContent(relativePath: String, snapshot: TurnSnapshot): String? {
        val absPath = PathUtil.resolveProjectPath(project, relativePath)

        // 1. Captured Content (Highest Priority)
        snapshot.capturedBeforeContent[relativePath]?.let {
            return it
        }
        
        // Note: We deliberately DO NOT check live capturedBeforeContent here.
        // Isolation requires we stick to the snapshot. Late events belong to the next turn.

        // 2. LocalHistory Baseline
        if (snapshot.baselineLabel != null && absPath != null) {
            try {
                snapshot.baselineLabel.getByteContent(absPath)?.let {
                    return String(it.bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                logger.info("[Turn #${snapshot.turnNumber}] LocalHistory lookup failed for $relativePath: ${e.message}")
            }
        }

        // 3. Known State
        return snapshot.knownFileStates[relativePath]
    }
    
    fun getLiveCapturedFilePaths(): Set<String> {
        return capturedBeforeContent.keys.toSet()
    }
    
    fun getLiveVfsChangedFiles(): Set<String> {
        return vfsChangedFiles.toSet()
    }
    
    fun getKnownFilePaths(): Set<String> {
        return lastKnownFileStates.keys.toSet()
    }

    fun updateKnownState(files: List<String>) {
        files.forEach { rawPath ->
            val absPath = PathUtil.resolveProjectPath(project, rawPath)
            if (absPath != null) {
                val file = File(absPath)
                if (file.exists() && file.isFile) {
                    val relativePath = PathUtil.relativizeToProject(project, absPath)
                    try {
                        val content = file.readText()
                        // Ensure we store using the canonical relative path key
                        lastKnownFileStates[relativePath] = content
                        logger.info("[Turn #$turnNumber] Updated known state (late refresh) for $relativePath")
                    } catch (e: Exception) {
                        logger.warn("[Turn #$turnNumber] Failed to update known state for $relativePath: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Resolve the "before" content for a file using a snapshot's baseline.
     */
    fun resolveBeforeContent(relativePath: String, diff: FileDiff, snapshot: TurnSnapshot): String {
        val absPath = PathUtil.resolveProjectPath(project, relativePath)
        val serverBefore = diff.before

        // Explicit New File Safety: If server says before is empty and intent is Create (after not empty),
        // we MUST trust the server. This prevents reading the AI's newly written content from disk
        // as the "before" state, which would result in an empty (Before==After) diff.
        if (serverBefore.isEmpty() && diff.after.isNotEmpty()) {
            logger.info("[Turn #${snapshot.turnNumber}] Server declared NEW file: $relativePath. Forcing before to empty.")
            return ""
        }

        // VFS Creation Safety: If we detected a physical creation event, also force before to empty.
        if (serverBefore.isEmpty() && relativePath in snapshot.aiCreatedFiles) {
            logger.info("[Turn #${snapshot.turnNumber}] VFS detected NEW file: $relativePath. Forcing before to empty.")
            return ""
        }

        // Try to get content from snapshot sources
        getSnapshotBeforeContent(relativePath, snapshot)?.let { 
            if (it.isNotEmpty() || serverBefore.isEmpty()) {
                 logger.info("[Turn #${snapshot.turnNumber}] Resolved before content for $relativePath")
                 return it
            }
        }
        
        // 4. Fallback: If Server Before is empty but file exists on disk -> Read Disk
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

        // 5. Default: Server Before
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
                        
                        // Update Known State for next turn safety
                        if (entry.isNewFile) {
                            lastKnownFileStates.remove(path)
                        } else {
                            lastKnownFileStates[path] = beforeContent
                        }
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
        vfsChangedFiles.clear()
        serverEditedFiles.clear()
        aiCreatedFiles.clear()
        userEditedFiles.clear()
        pendingDiffs.clear()
        lastKnownFileStates.clear()
        capturedBeforeContent.clear()
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
    val vfsChangedFiles: Set<String>,
    val serverEditedFiles: Set<String>,
    val aiCreatedFiles: Set<String>,
    val userEditedFiles: Set<String>,
    val baselineLabel: Label?,
    val knownFileStates: Map<String, String> = emptyMap(),
    val capturedBeforeContent: Map<String, String> = emptyMap()
)

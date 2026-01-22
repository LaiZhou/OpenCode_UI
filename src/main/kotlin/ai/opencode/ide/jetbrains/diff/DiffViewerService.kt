package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

import java.lang.ref.WeakReference

/**
 * Service for showing diffs using JetBrains native DiffManager.
 */
@Service(Service.Level.PROJECT)
class DiffViewerService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(DiffViewerService::class.java)
    
    // Track the last opened diff file to close it before opening a new one
    private var lastOpenedDiffFile: WeakReference<VirtualFile>? = null

    /**
     * Show diff starting at the given file.
     *
     * To match Claude Code's navigation UX, this opens a multi-file chain whenever
     * possible, so users get left/right (file) navigation in the same view.
     */
    fun showDiff(entry: DiffEntry) {
        val allEntries = project.service<SessionManager>().getAllDiffEntries()
        val index = allEntries.indexOfFirst { it.diff.file == entry.diff.file }

        if (index >= 0 && allEntries.size > 1) {
            showMultiFileDiff(allEntries, index)
            return
        }

        // Fallback: single diff when there is only one pending change.
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(entry.diff.file)
        val beforeText = resolveBeforeContent(entry)
        val afterText = resolveAfterContent(entry)
        val beforeContent = DiffContentFactory.getInstance().create(project, beforeText, fileType)
        val afterContent = DiffContentFactory.getInstance().create(project, afterText, fileType)

        val total = allEntries.size.coerceAtLeast(1)
        val currentIndex = (index + 1).coerceAtLeast(1)
        val sessionManager = project.service<SessionManager>()
        val hasUserEdits = sessionManager.hasUserEdits(entry.diff.file)
        val afterTitleSuffix = if (hasUserEdits) " (With User Edits)" else ""

        val request = SimpleDiffRequest(
            "[$currentIndex/$total] ${entry.diff.file}",
            beforeContent,
            afterContent,
            "Original",
            "OpenCode (Modified)$afterTitleSuffix"
        )


        decorateRequest(request, entry)
        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * Show diffs for multiple entries in a chain (allows navigation between files).
     *
     * Aligned with Claude Code:
     * - Default shows the requested index (or the last one in the batch)
     * - Support navigation arrows (<- -> for files, ^ v for changes)
     */
    fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int? = null) {
        // 1. Close previously opened diff window to avoid accumulation
        closeLastOpenedDiff()
        
        val total = entries.size
        val requests = entries.mapIndexed { index, entry ->
            val fileType = FileTypeManager.getInstance()
                .getFileTypeByFileName(entry.diff.file)
            
            // Show progress first (e.g., "[1/8] file.txt") to ensure it's visible even if truncated
            val progressTitle = "[${index + 1}/$total] ${entry.diff.file}"

            val hasUserEdits = project.service<SessionManager>().hasUserEdits(entry.diff.file)
            val afterTitleSuffix = if (hasUserEdits) " (With User Edits)" else ""

            val beforeText = resolveBeforeContent(entry)
            val afterText = resolveAfterContent(entry)

            val request = SimpleDiffRequest(
                progressTitle,
                DiffContentFactory.getInstance().create(project, beforeText, fileType),
                DiffContentFactory.getInstance().create(project, afterText, fileType),
                "Original",
                "Modified (OpenCode)$afterTitleSuffix"
            )
            decorateRequest(request, entry)
            request
        }

        val chain = SimpleDiffRequestChain(requests)
        
        // Aligned with Claude Code experience: 
        // If no index provided (e.g. initial auto-open), show the FIRST file to reflect chronological order ("replay thought process").
        // If index provided (e.g. from ToolWindow or Auto-advance), use that index.
        val targetIndex = initialIndex ?: 0
        if (targetIndex in requests.indices) {
            chain.index = targetIndex
        }

        // 2. Capture the newly opened diff file (use short-lived connection that auto-disconnects)
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                // Heuristic: The file opened immediately after showDiff is likely our diff window
                // Diff files typically have internal types like DiffVirtualFile or ChainDiffVirtualFile
                val isDiffFile = file.javaClass.name.contains("DiffVirtualFile") || 
                                 file.javaClass.name.contains("ChainDiffVirtualFile")
                
                if (isDiffFile) {
                    logger.debug("[OpenCode] Captured opened diff file: ${file.name}")
                    lastOpenedDiffFile = WeakReference(file)
                    connection.disconnect()
                }
            }
        })

        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
        
        // Ensure listener is cleaned up if no file was opened (e.g. error or reuse)
        ApplicationManager.getApplication().invokeLater {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Ignore if already disconnected
            }
        }
    }
    
    private fun closeLastOpenedDiff() {
        val file = lastOpenedDiffFile?.get()
        if (file != null && file.isValid) {
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).closeFile(file)
            }
        }
        lastOpenedDiffFile = null
    }

    /**
     * Get diff after content.
     */
    private fun resolveAfterContent(entry: DiffEntry): String {
        return entry.diff.after
    }

    /**
     * Get diff before content.
     * 
     * Delegates to SessionManager.resolveBeforeContent() to ensure
     * CONSISTENCY between what is displayed and what Reject restores.
     */
    private fun resolveBeforeContent(entry: DiffEntry): String {
        return project.service<SessionManager>().resolveBeforeContent(entry.diff.file, entry.diff.before)
    }

    private fun decorateRequest(request: SimpleDiffRequest, entry: DiffEntry) {
        val actions = listOf(
            AcceptFromDiffEditorAction(entry.diff.file),
            RejectFromDiffEditorAction(entry.diff.file)
        )

        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)
    }

    override fun dispose() {
        logger.debug("Disposing DiffViewerService")
        // Close any open diff windows
        closeLastOpenedDiff()
    }
}

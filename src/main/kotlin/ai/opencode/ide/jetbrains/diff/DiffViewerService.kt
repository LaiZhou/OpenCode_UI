package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.util.PathUtil

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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
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
        val titleSuffix = if (hasLocalChanges(entry)) " (Local Modified)" else ""
        
        val request = SimpleDiffRequest(
            "${entry.diff.file} ($currentIndex of $total)$titleSuffix",
            beforeContent,
            afterContent,
            "Original",
            "OpenCode (Modified)"
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
            
            // Show filename and progress (e.g., "file.txt (1 of 8)")
            val title = "${entry.diff.file} (${index + 1} of $total)"

            // Get diff content, fallback to disk read if server returns empty (e.g. for non-ASCII filenames)
            val beforeText = resolveBeforeContent(entry)
            val afterText = resolveAfterContent(entry)

            val titleSuffix = if (hasLocalChanges(entry)) " (Local Modified)" else ""
            val request = SimpleDiffRequest(
                "$title$titleSuffix",
                DiffContentFactory.getInstance().create(project, beforeText, fileType),
                DiffContentFactory.getInstance().create(project, afterText, fileType),
                "Original",
                "Modified (OpenCode)"
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
     * If server returns empty but additions > 0 (server bug), fallback to reading from disk.
     */
    private fun resolveAfterContent(entry: DiffEntry): String {
        val afterText = entry.diff.after
        
        // If after has content, return directly
        if (afterText.isNotEmpty()) {
            return afterText
        }
        
        // If after is empty but additions > 0, server didn't return content correctly, try loading from disk
        if (entry.diff.additions > 0) {
            logger.info("[OpenCode] Server returned empty 'after' for ${entry.diff.file} (additions=${entry.diff.additions}), loading from disk")
            val absolutePath = resolveAbsolutePath(entry.diff.file)
            if (absolutePath != null) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
                if (virtualFile != null && virtualFile.exists()) {
                    try {
                        val diskContent = VfsUtilCore.loadText(virtualFile)
                        logger.info("[OpenCode] Loaded ${diskContent.length} chars from disk for: ${entry.diff.file}")
                        return diskContent
                    } catch (e: Exception) {
                        logger.warn("[OpenCode] Failed to load file from disk: ${entry.diff.file}", e)
                    }
                }
            }
        }
        
        return afterText
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

    private fun hasLocalChanges(entry: DiffEntry): Boolean {
        val absolutePath = resolveAbsolutePath(entry.diff.file) ?: return false
        val file = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return false
        if (!file.exists() || file.isDirectory) return false

        return try {
            val diskContent = VfsUtilCore.loadText(file)
            val expectedContent = resolveAfterContent(entry)
            diskContent != expectedContent
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Convert relative path to absolute path.
     */
    private fun resolveAbsolutePath(filePath: String): String? {
        return PathUtil.resolveProjectPath(project, filePath)
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

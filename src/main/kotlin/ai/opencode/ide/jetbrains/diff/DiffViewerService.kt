package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
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
open class DiffViewerService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(DiffViewerService::class.java)
    private var lastOpenedDiffFile: WeakReference<VirtualFile>? = null

    /**
     * Show diffs for multiple entries in a chain.
     * Note: entries should have resolvedBefore pre-populated on a background thread.
     */
    open fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int? = null) {
        closeLastOpenedDiff()

        if (entries.isEmpty()) {
            logger.info("[DiffViewer] No entries to show")
            return
        }

        val total = entries.size
        val requests = entries.mapIndexed { index, entry ->
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(entry.file)
            val title = "[${index + 1}/$total] ${entry.file}"
            val afterTitle = if (entry.hasUserEdits) "Modified (OpenCode + User)" else "Modified (OpenCode)"

            // Use pre-resolved before content (no LocalHistory call on EDT)
            val beforeText = entry.beforeContent
            val afterText = entry.diff.after

            val request = SimpleDiffRequest(
                title,
                DiffContentFactory.getInstance().create(project, beforeText, fileType),
                DiffContentFactory.getInstance().create(project, afterText, fileType),
                "Original",
                afterTitle
            )
            
            // Add Accept/Reject actions
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(
                AcceptFromDiffEditorAction(entry.file),
                RejectFromDiffEditorAction(entry.file)
            ))
            
            request
        }

        val chain = SimpleDiffRequestChain(requests)
        chain.index = (initialIndex ?: 0).coerceIn(0, requests.lastIndex)

        // Track the opened diff file for cleanup
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (file.javaClass.name.contains("DiffVirtualFile")) {
                    lastOpenedDiffFile = WeakReference(file)
                    connection.disconnect()
                }
            }
        })

        logger.info("[DiffViewer] Showing ${entries.size} files")
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)

        // Cleanup listener if not triggered
        ApplicationManager.getApplication().invokeLater {
            try { connection.disconnect() } catch (_: Exception) {}
        }
    }

    private fun closeLastOpenedDiff() {
        lastOpenedDiffFile?.get()?.takeIf { it.isValid }?.let { file ->
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).closeFile(file)
            }
        }
        lastOpenedDiffFile = null
    }

    override fun dispose() {
        closeLastOpenedDiff()
    }
}

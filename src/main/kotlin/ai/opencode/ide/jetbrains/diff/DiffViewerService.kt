package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project

/**
 * Service for showing diffs using JetBrains native DiffManager.
 */
@Service(Service.Level.PROJECT)
class DiffViewerService(private val project: Project) {

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
        val beforeContent = DiffContentFactory.getInstance().create(project, entry.diff.before, fileType)
        val afterContent = DiffContentFactory.getInstance().create(project, entry.diff.after, fileType)

        val request = SimpleDiffRequest(
            "OpenCode Diff: ${entry.diff.file}",
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
        val total = entries.size
        val requests = entries.mapIndexed { index, entry ->
            val fileType = FileTypeManager.getInstance()
                .getFileTypeByFileName(entry.diff.file)
            
            // Progress title: "file.kt (1 of 5)"
            val title = if (total > 1) "${entry.diff.file} (${index + 1} of $total)" else entry.diff.file

            val request = SimpleDiffRequest(
                title,
                DiffContentFactory.getInstance().create(project, entry.diff.before, fileType),
                DiffContentFactory.getInstance().create(project, entry.diff.after, fileType),
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

        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }

    private fun decorateRequest(request: SimpleDiffRequest, entry: DiffEntry) {
        val actions = listOf(
            AcceptFromDiffEditorAction(entry.diff.file),
            RejectFromDiffEditorAction(entry.diff.file)
        )

        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)
    }
}

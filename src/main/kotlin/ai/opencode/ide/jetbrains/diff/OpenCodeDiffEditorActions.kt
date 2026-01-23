package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages

private fun findEntryIndex(entries: List<DiffEntry>, filePath: String): Int {
    val idx = entries.indexOfFirst { it.file == filePath }
    return if (idx >= 0) idx else 0
}

private fun closeCurrentDiffIfPossible(e: AnActionEvent) {
    val project = e.project ?: return

    // 尝试获取当前的 VirtualFile（Diff 视图通常是一个虚拟文件）
    val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    
    // 只要文件存在，就尝试关闭它。DiffVirtualFile 通常是临时的。
    // 如果是在普通编辑器里（不太可能，因为 Action 只在 Diff 上下文显示），关闭也是合理的。
    FileEditorManager.getInstance(project).closeFile(virtualFile)
}

private fun openNextDiff(project: com.intellij.openapi.project.Project, previousIndex: Int, closeEvent: AnActionEvent?) {
    // 1. 无论是否还有剩余，都先关闭当前窗口
    if (closeEvent != null) {
        closeCurrentDiffIfPossible(closeEvent)
    }

    val sessionManager = project.service<SessionManager>()
    val remaining = sessionManager.getAllDiffEntries()

    if (remaining.isEmpty()) {
        return
    }

    // 2. 还有剩余 Diff，打开新的 Diff 窗口
    val nextIndex = previousIndex.coerceIn(0, remaining.lastIndex)
    
    // 使用 invokeLater 确保关闭操作完成后再打开新窗口，体验更流畅
    ApplicationManager.getApplication().invokeLater {
        project.service<DiffViewerService>().showMultiFileDiff(remaining, nextIndex)
    }
}

class AcceptFromDiffEditorAction(private val filePath: String) : AnAction() {
    init {
        templatePresentation.text = "Accept"
        templatePresentation.description = "Accept this change (git add)"
        templatePresentation.icon = AllIcons.Actions.Checked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sessionManager = project.service<SessionManager>()

        val before = sessionManager.getAllDiffEntries()
        val previousIndex = findEntryIndex(before, filePath)

        val entry = sessionManager.getDiffForFile(filePath)
        if (entry != null) {
            // Use the callback to navigate after accept completes
            sessionManager.acceptDiff(entry) { success ->
                if (success) {
                    openNextDiff(project, previousIndex, e)
                } else {
                    Messages.showWarningDialog(project, "Failed to stage $filePath", "Accept Failed")
                }
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(project, "Diff entry not found for $filePath", "Accept Failed")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && project.service<SessionManager>().getDiffForFile(filePath) != null
        e.presentation.isEnabled = enabled
    }
}

class RejectFromDiffEditorAction(private val filePath: String) : AnAction() {
    init {
        templatePresentation.text = "Reject"
        templatePresentation.description = "Reject this change (restore original)"
        templatePresentation.icon = AllIcons.Actions.Rollback
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val sessionManager = project.service<SessionManager>()
        val entry: DiffEntry = sessionManager.getDiffForFile(filePath) ?: return

        // Build confirmation message based on file state
        val isNewFile = entry.isNewFile
        val hasUserEdits = entry.hasUserEdits
        
        val message = buildString {
            if (isNewFile) {
                append("Delete new file '${entry.file}'?\n\n")
                append("This file was created by OpenCode and will be removed.")
            } else {
                append("Reject changes to '${entry.file}'?\n\n")
                append("The file will be restored to its state before OpenCode modified it.")
            }
            
            // Per design doc: warn if user has edits in this file
            if (hasUserEdits) {
                append("\n\n")
                append("WARNING: You have also edited this file. Your changes will be lost!")
            }
        }

        val confirm = Messages.showYesNoDialog(
            project,
            message,
            if (hasUserEdits) "Confirm Reject (Data Loss Warning)" else "Confirm Reject",
            Messages.getQuestionIcon()
        )

        if (confirm != Messages.YES) return

        val before = sessionManager.getAllDiffEntries()
        val previousIndex = findEntryIndex(before, filePath)

        // Use the callback to navigate after reject completes (no more ProgressManager race)
        sessionManager.rejectDiff(entry) { success ->
            if (success) {
                openNextDiff(project, previousIndex, e)
            } else {
                Messages.showWarningDialog(project, "Failed to restore $filePath", "Reject Failed")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val entry = if (project != null) project.service<SessionManager>().getDiffForFile(filePath) else null
        e.presentation.isEnabled = entry != null
    }
}

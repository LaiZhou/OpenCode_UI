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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

private fun findEntryIndex(entries: List<DiffEntry>, filePath: String): Int {
    val idx = entries.indexOfFirst { it.diff.file == filePath }
    return if (idx >= 0) idx else 0
}

private fun closeCurrentDiffIfPossible(e: AnActionEvent) {
    val project = e.project ?: return

    val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
    if (virtualFile != null && virtualFile.javaClass.name.endsWith(".DiffVirtualFile")) {
        FileEditorManager.getInstance(project).closeFile(virtualFile)
        return
    }

}

private fun openNextDiff(project: com.intellij.openapi.project.Project, previousIndex: Int, closeEvent: AnActionEvent?) {
    val sessionManager = project.service<SessionManager>()
    val remaining = sessionManager.getAllDiffEntries()

    if (remaining.isEmpty()) {
        if (closeEvent != null) closeCurrentDiffIfPossible(closeEvent)
        return
    }

    val nextIndex = previousIndex.coerceIn(0, remaining.lastIndex)
    project.service<DiffViewerService>().showMultiFileDiff(remaining, nextIndex)

    if (closeEvent != null) closeCurrentDiffIfPossible(closeEvent)
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

        val success = sessionManager.acceptDiff(filePath)

        ApplicationManager.getApplication().invokeLater {
            if (success) {
                openNextDiff(project, previousIndex, e)
            } else {
                Messages.showWarningDialog(project, "Failed to stage changes.", "Accept Failed")
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

        // Provide more context in the confirmation dialog
        val isNewFile = entry.diff.before.isEmpty()
        val message = if (isNewFile) {
            "Delete new file '${entry.diff.file}'?\n\nThis file was created by OpenCode and will be removed."
        } else {
            "Reject changes to '${entry.diff.file}'?\n\nThe file will be restored to its state before OpenCode modified it."
        }

        val confirm = Messages.showYesNoDialog(
            project,
            message,
            "Confirm Reject",
            Messages.getQuestionIcon()
        )

        if (confirm != Messages.YES) return

        val before = sessionManager.getAllDiffEntries()
        val previousIndex = findEntryIndex(before, filePath)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "OpenCode: Rejecting changes", false) {
            private var ok: Boolean = false

            override fun run(indicator: ProgressIndicator) {
                ok = sessionManager.rejectDiff(filePath)
            }

            override fun onSuccess() {
                if (!ok) {
                    Messages.showWarningDialog(
                        project, 
                        "Failed to restore file. Check Local History for recovery options.", 
                        "Reject Failed"
                    )
                    return
                }

                openNextDiff(project, previousIndex, e)
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val entry = if (project != null) project.service<SessionManager>().getDiffForFile(filePath) else null
        e.presentation.isEnabled = entry != null
    }
}

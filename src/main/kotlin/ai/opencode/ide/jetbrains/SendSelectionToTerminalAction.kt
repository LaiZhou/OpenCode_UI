package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Sends file path references to the OpenCode terminal.
 * 
 * - Editor selection: @path#Lstart-end (NO content, just the reference)
 * - Project View selection: @path for each file/directory
 *
 * If the OpenCode terminal is not open yet, the plugin will create/focus it
 * and then paste the reference (Claude-style UX).
 */
class SendSelectionToTerminalAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val openCodeService = project.service<OpenCodeService>()

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val textToSend = StringBuilder()

        if (editor != null) {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val relativePath = file?.let { getRelativePath(project, it) }

            if (relativePath != null) {
                val referencePath = formatPathReference(relativePath)
                if (editor.selectionModel.hasSelection()) {
                    // Editor selection: only @path#Lstart-end (NO content)
                    val selectionModel = editor.selectionModel
                    val document = editor.document
                    val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
                    val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

                    textToSend.append("@$referencePath#L$startLine-$endLine")
                } else {
                    // No selection: share current file reference
                    textToSend.append("@$referencePath")
                }
            }
        } else if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            // Project View selection: @path per file/directory
            for (file in virtualFiles) {
                val relativePath = getRelativePath(project, file)
                val referencePath = formatPathReference(relativePath)
                textToSend.append("@$referencePath ")
            }
        }

        if (textToSend.isNotEmpty()) {
            val payload = textToSend.toString().trimEnd() + " "
            openCodeService.focusOrCreateTerminalAndPaste(payload)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val hasEditorContext = editor != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        val hasFileSelection = virtualFiles != null && virtualFiles.isNotEmpty()

        // Always available even if terminal isn't created yet
        e.presentation.isEnabledAndVisible = hasEditorContext || hasFileSelection
    }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        return PathUtil.relativizeToProject(project, file.path)
    }

    private fun formatPathReference(path: String): String {
        return if (path.contains(" ")) {
            "\"$path\""
        } else {
            path
        }
    }
}

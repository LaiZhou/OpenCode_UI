package ai.opencode.jetbrains

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
 * This action is only enabled when an OpenCode terminal already exists.
 */
class SendSelectionToTerminalAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val openCodeService = project.service<OpenCodeService>()
        
        // Only proceed if OpenCode terminal exists
        if (!openCodeService.hasOpenCodeTerminal()) {
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val textToSend = StringBuilder()

        if (editor != null && editor.selectionModel.hasSelection()) {
            // Editor selection: only @path#Lstart-end (NO content)
            val selectionModel = editor.selectionModel
            val document = editor.document
            val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
            val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val relativePath = file?.let { getRelativePath(project, it) } ?: "unknown"

            textToSend.append("@$relativePath#L$startLine-$endLine")
        } else if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            // Project View selection: @path per file/directory
            for (file in virtualFiles) {
                val relativePath = getRelativePath(project, file)
                textToSend.append("@$relativePath ")
            }
        }

        if (textToSend.isNotEmpty()) {
            openCodeService.pasteToTerminal(textToSend.toString())
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Only show menu if OpenCode terminal exists
        val hasOpenCodeTerminal = project.service<OpenCodeService>().hasOpenCodeTerminal()
        if (!hasOpenCodeTerminal) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val hasEditorSelection = editor != null && editor.selectionModel.hasSelection()
        val hasFileSelection = virtualFiles != null && virtualFiles.isNotEmpty()

        e.presentation.isEnabledAndVisible = hasEditorSelection || hasFileSelection
    }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        val projectBaseDir = project.basePath ?: return file.path
        val filePath = file.path
        return if (filePath.startsWith(projectBaseDir)) {
            filePath.substring(projectBaseDir.length).removePrefix("/")
        } else {
            filePath
        }
    }
}

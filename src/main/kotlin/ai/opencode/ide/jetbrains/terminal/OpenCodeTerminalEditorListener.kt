package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * Listener to handle proper disposal of OpenCode terminal widgets.
 * 
 * Implements a "Grace Period" strategy:
 * When a tab is closed, we wait for a short period before disposing the widget.
 * If the tab is merely being moved (closed and immediately re-opened), 
 * or if it's still open in another splitter, the disposal will be cancelled or aborted.
 */
class OpenCodeTerminalEditorListener : FileEditorManagerListener {
    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file is OpenCodeTerminalVirtualFile) {
            // Schedule disposal with a delay (5 seconds).
            // Pass the project reference so we can check if the file is still open later.
            OpenCodeTerminalFileEditorProvider.scheduleDisposal(file, source.project)
        }
    }
}

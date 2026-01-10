package ai.opencode.jetbrains

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView
import kotlin.text.iterator

/**
 * Project-scoped helper for interacting with the IDE Terminal.
 *
 * This plugin uses a single terminal tab per project, identified by its tab name: "OpenCode".
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) {

    companion object {
        private const val TERMINAL_TOOLWINDOW_ID = "Terminal"
        private const val OPEN_CODE_TAB_NAME = "OpenCode"
    }

    /**
     * Checks if an "OpenCode" terminal tab exists.
     */
    fun hasOpenCodeTerminal(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return false
        val contentManager = toolWindow.contentManager
        return contentManager.contents.any { it.displayName == OPEN_CODE_TAB_NAME }
    }

    /**
     * Focus existing "OpenCode" terminal tab, or create a new one and run `opencode`.
     */
    fun focusOrCreateTerminal() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return
        val terminalView = TerminalView.getInstance(project)
        val contentManager = toolWindow.contentManager

        // Try to find existing "OpenCode" tab
        val existingContent = contentManager.contents.find { it.displayName == OPEN_CODE_TAB_NAME }

        if (existingContent != null) {
            // Focus existing tab
            contentManager.setSelectedContent(existingContent)
            toolWindow.activate(null)
        } else {
            // Create new terminal widget
            val widget = terminalView.createLocalShellWidget(project.basePath, OPEN_CODE_TAB_NAME)

            // The createLocalShellWidget may not set displayName correctly.
            // Find the newly created content and rename it.
            val newContent = contentManager.contents.lastOrNull()
            newContent?.displayName = OPEN_CODE_TAB_NAME

            // Select the new tab
            newContent?.let { contentManager.setSelectedContent(it) }

            // Run opencode command
            widget.executeCommand("opencode")

            toolWindow.activate(null)
        }
    }

    /**
     * Pastes text to the OpenCode terminal.
     * Returns false if no OpenCode terminal exists.
     */
    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOLWINDOW_ID) ?: return false
        val terminalView = TerminalView.getInstance(project)
        val contentManager = toolWindow.contentManager

        // Only paste if OpenCode terminal already exists - do NOT create new terminal
        val openCodeContent = contentManager.contents.find { it.displayName == OPEN_CODE_TAB_NAME }
        if (openCodeContent == null) {
            // No OpenCode terminal exists, do nothing
            return false
        }

        // Find widget for existing content
        val widget = terminalView.getWidgets().firstOrNull { w ->
            contentManager.getContent(w.component) == openCodeContent
        } as? ShellTerminalWidget

        if (widget == null) {
            return false
        }

        // Type text into terminal
        val ttyConnector = widget.ttyConnector
        if (ttyConnector != null) {
            for (char in text) {
                ttyConnector.write(char.toString())
            }
        }

        contentManager.setSelectedContent(openCodeContent)
        toolWindow.activate(null)
        return true
    }
}

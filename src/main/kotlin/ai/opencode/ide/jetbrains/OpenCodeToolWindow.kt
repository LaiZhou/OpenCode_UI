package ai.opencode.ide.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

/**
 * OpenCode sidebar icon.
 *
 * Clicking the sidebar icon triggers focusOrCreateTerminal() - no panel content is displayed.
 * The tool window is immediately hidden after triggering the action.
 */
class OpenCodeToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Add dummy content to avoid "Empty Tool Window" warning
        val content = ContentFactory.getInstance().createContent(JPanel(), "", false)
        toolWindow.contentManager.addContent(content)

        // Do NOT focus terminal on creation to respect user's choice at IDE startup.
        // The terminal will only be created when the user explicitly clicks the icon
        // or uses the keyboard shortcut.

        // If the tool window is visible due to restored IDE state, hide it quietly.
        if (toolWindow.isVisible) {
            toolWindow.hide()
        }

        // Listen for when this tool window is shown (sidebar icon clicked)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id == "OpenCode") {
                        project.service<OpenCodeService>().focusOrCreateTerminal()
                        tw.hide()
                    }
                }
            }
        )
    }
}

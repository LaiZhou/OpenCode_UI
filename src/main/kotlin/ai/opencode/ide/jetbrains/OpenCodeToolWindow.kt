package ai.opencode.ide.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * OpenCode sidebar icon.
 *
 * The service owns the terminal widget. The tool window only owns its Content wrapper,
 * so hiding the stripe window does not terminate the terminal session.
 */
class OpenCodeToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<OpenCodeService>()
        service.bindToolWindow(toolWindow)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id == toolWindow.id) {
                        service.onToolWindowShown()
                    }
                }
            }
        )
    }
}

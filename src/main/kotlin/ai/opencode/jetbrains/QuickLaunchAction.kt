package ai.opencode.jetbrains

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Quick Launch Action: 
 * Opens or focuses a terminal tab named "OpenCode".
 */
class QuickLaunchAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<OpenCodeService>().focusOrCreateTerminal()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

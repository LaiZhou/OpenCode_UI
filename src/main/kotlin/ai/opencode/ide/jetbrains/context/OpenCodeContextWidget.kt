package ai.opencode.ide.jetbrains.context

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Status bar widget factory for showing current OpenCode context.
 */
class OpenCodeContextWidgetFactory : StatusBarWidgetFactory {
    
    companion object {
        const val WIDGET_ID = "OpenCodeContext"
    }

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "OpenCode Context"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return OpenCodeContextWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * Status bar widget that displays the current selection context.
 * Shows the current file and line range that will be shared with OpenCode.
 * 
 * Implements IconPresentation to show both an icon and a tooltip in the status bar.
 */
class OpenCodeContextWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {

    private var statusBar: StatusBar? = null
    private var tooltipText: String = "OpenCode: No active context"
    private val icon: Icon = IconLoader.getIcon("/icons/opencode.svg", OpenCodeContextWidget::class.java)
    
    private val contextListener: (SelectionContextService.SelectionContext?) -> Unit = { context ->
        updateFromContext(context)
    }

    override fun ID(): String = OpenCodeContextWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        
        // Register for context updates
        val contextService = project.service<SelectionContextService>()
        contextService.addContextListener(contextListener)
    }

    override fun dispose() {
        try {
            val contextService = project.service<SelectionContextService>()
            contextService.removeContextListener(contextListener)
        } catch (e: Exception) {
            // Service may already be disposed
        }
        statusBar = null
    }

    // IconPresentation implementation
    
    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String = tooltipText

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { _ ->
        // On click, share the current context to terminal
        val contextService = project.service<SelectionContextService>()
        contextService.shareCurrentContext()
    }

    private fun updateFromContext(context: SelectionContextService.SelectionContext?) {
        tooltipText = if (context != null) {
            val displayPath = StringUtil.shortenPathWithEllipsis(context.toDisplayString(), 50)
            "OpenCode Context: $displayPath\nClick to share with OpenCode Terminal"
        } else {
            "OpenCode: No active context"
        }
        
        statusBar?.updateWidget(ID())
    }
}

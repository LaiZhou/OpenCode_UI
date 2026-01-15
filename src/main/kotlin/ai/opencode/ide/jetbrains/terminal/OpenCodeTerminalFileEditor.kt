package ai.opencode.ide.jetbrains.terminal

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.JComponent

/**
 * FileEditor implementation that wraps a terminal widget for display in the editor area.
 */
class OpenCodeTerminalFileEditor(
    private val project: Project,
    private val virtualFile: OpenCodeTerminalVirtualFile,
    val terminalWidget: ShellTerminalWidget?
) : UserDataHolderBase(), FileEditor {

    private val logger = Logger.getInstance(OpenCodeTerminalFileEditor::class.java)
    private val propertyChangeSupport = PropertyChangeSupport(this)
    private val isDisposed = AtomicBoolean(false)
    
    // Placeholder component for when widget is null (e.g. IDE restore)
    private val placeholderComponent by lazy {
        JLabel("OpenCode Terminal disconnected. Please close and reopen.", SwingConstants.CENTER)
    }

    init {
        logger.debug("Created OpenCode terminal editor for: ${virtualFile.name} (widget=${if(terminalWidget!=null) "present" else "null"})")
    }

    override fun getComponent(): JComponent = terminalWidget?.component ?: placeholderComponent

    override fun getPreferredFocusedComponent(): JComponent? = terminalWidget?.preferredFocusableComponent

    override fun getName(): String = "OpenCode Terminal"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !isDisposed.get() && !project.isDisposed

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? = null

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            logger.debug("Disposing OpenCode terminal editor for: ${virtualFile.name}")
            try {
                terminalWidget?.dispose()
                for (listener in propertyChangeSupport.propertyChangeListeners) {
                    propertyChangeSupport.removePropertyChangeListener(listener)
                }
            } catch (e: Exception) {
                logger.warn("Error disposing terminal widget", e)
            }
        }
    }

    override fun getFile(): VirtualFile = virtualFile
}

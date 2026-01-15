package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.util.*

/**
 * Provider for OpenCode terminal file editors.
 * 
 * Uses WeakHashMap to avoid memory leaks - automatically cleans up when VirtualFile is GC'd.
 */
class OpenCodeTerminalFileEditorProvider : FileEditorProvider, DumbAware {

    companion object {
        private val logger = Logger.getInstance(OpenCodeTerminalFileEditorProvider::class.java)
        
        // Use WeakHashMap to avoid memory leaks
        private val terminalWidgets = Collections.synchronizedMap(
            WeakHashMap<OpenCodeTerminalVirtualFile, ShellTerminalWidget>()
        )
        
        fun registerWidget(file: OpenCodeTerminalVirtualFile, widget: ShellTerminalWidget) {
            logger.debug("Registering terminal widget for file: ${file.name}")
            terminalWidgets[file] = widget
        }
        
        fun unregisterWidget(file: OpenCodeTerminalVirtualFile) {
            logger.debug("Unregistering terminal widget for file: ${file.name}")
            terminalWidgets.remove(file)
        }
        
        /**
         * Clear all registered widgets.
         * MUST be called during plugin unload to support dynamic plugin loading.
         * Static maps are not automatically cleaned up when plugin is unloaded.
         */
        fun clearAll() {
            logger.debug("Clearing all terminal widgets for dynamic plugin unload")
            terminalWidgets.clear()
        }
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is OpenCodeTerminalVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(file is OpenCodeTerminalVirtualFile) { "File must be OpenCodeTerminalVirtualFile" }
        
        val widget = terminalWidgets[file]
        
        if (widget == null) {
            logger.error("Terminal widget not registered for file: ${file.name}")
            throw IllegalStateException("Terminal widget not registered for file: ${file.name}. Make sure registerWidget() is called before opening the file.")
        }
        
        logger.debug("Creating terminal editor for file: ${file.name}")
        return OpenCodeTerminalFileEditor(project, file, widget)
    }

    override fun getEditorTypeId(): String = "opencode-terminal-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Provider for OpenCode terminal file editors.
 * 
 * Manages the lifecycle of terminal widgets.
 * Implements "Delayed Disposal" to distinguish between closing a tab (kill) 
 * and moving a tab (keep alive).
 */
class OpenCodeTerminalFileEditorProvider : FileEditorProvider, DumbAware {

    companion object {
        private val logger = Logger.getInstance(OpenCodeTerminalFileEditorProvider::class.java)
        
        // Use ConcurrentHashMap to manage lifecycle manually (Strong References)
        private val terminalWidgets = ConcurrentHashMap<OpenCodeTerminalVirtualFile, ShellTerminalWidget>()
        
        // Track pending disposal tasks
        private val disposalTasks = ConcurrentHashMap<OpenCodeTerminalVirtualFile, ScheduledFuture<*>>()
        
        fun registerWidget(file: OpenCodeTerminalVirtualFile, widget: ShellTerminalWidget) {
            logger.debug("Registering terminal widget for file: ${file.name}")
            cancelDisposal(file) // Cancel any pending disposal if re-registering
            terminalWidgets[file] = widget
        }
        
        fun unregisterWidget(file: OpenCodeTerminalVirtualFile) {
            logger.debug("Unregistering terminal widget for file: ${file.name}")
            terminalWidgets.remove(file)
            cancelDisposal(file)
        }

        /**
         * Schedule widget for disposal with a safety delay (2 seconds).
         * This handles the "Tab Move" scenario where a tab is closed and immediately re-opened.
         */
        fun scheduleDisposal(file: OpenCodeTerminalVirtualFile, project: Project) {
            // Cancel any existing task first
            cancelDisposal(file)
            
            // Only schedule if we actually have a widget to dispose
            if (!terminalWidgets.containsKey(file)) return

            logger.debug("Scheduling disposal check for ${file.name} in 2000ms")
            
            val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                // Must run on EDT to check FileEditorManager status safely
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    
                    // CRITICAL CHECK: Is the file currently open?
                    // This handles cases where:
                    // 1. The file was moved to a new window (so it's open there)
                    // 2. The file is split (so it's open in another pane)
                    // 3. The file was re-opened very quickly
                    val isStillOpen = FileEditorManager.getInstance(project).isFileOpen(file)
                    
                    if (isStillOpen) {
                        logger.debug("Aborting disposal for ${file.name}: File is still open (move/split detected)")
                    } else {
                        logger.info("Disposing ${file.name}: File is truly closed")
                        disposeWidget(file)
                    }
                }
            }, 2000, TimeUnit.MILLISECONDS)
            
            disposalTasks[file] = task
        }

        /**
         * Cancel pending disposal.
         * Called when the file is re-opened (e.g. during tab move).
         */
        fun cancelDisposal(file: OpenCodeTerminalVirtualFile) {
            val task = disposalTasks.remove(file)
            if (task != null) {
                if (!task.isDone) {
                    task.cancel(false)
                    logger.debug("Cancelled disposal task for ${file.name}")
                }
            }
        }

        fun disposeWidget(file: OpenCodeTerminalVirtualFile) {
            // Remove from tasks map to keep it clean
            disposalTasks.remove(file)
            
            val widget = terminalWidgets.remove(file)
            if (widget != null) {
                logger.debug("Disposing terminal widget for file: ${file.name}")
                try {
                    widget.dispose()
                } catch (e: Exception) {
                    logger.warn("Error disposing terminal widget", e)
                }
            }
        }
        
        /**
         * Clear all registered widgets.
         * MUST be called during plugin unload (Project dispose).
         */
        fun clearAll() {
            logger.debug("Clearing all terminal widgets for dynamic plugin unload")
            // Cancel all pending tasks
            disposalTasks.values.forEach { it.cancel(false) }
            disposalTasks.clear()
            
            // Dispose all widgets
            terminalWidgets.keys.forEach { disposeWidget(it) }
            terminalWidgets.clear()
        }
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is OpenCodeTerminalVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(file is OpenCodeTerminalVirtualFile) { "File must be OpenCodeTerminalVirtualFile" }
        
        // Critical: Cancel any pending disposal since we are creating an editor for it!
        // This handles the "Fast Move" scenario where createEditor is called before the task runs.
        cancelDisposal(file)
        
        val widget = terminalWidgets[file]
        
        if (widget == null) {
            // This happens when IDE restores a session but our plugin hasn't re-initialized the terminal yet.
            // Or if the user closed the tab, waited >2s, and then re-opened it (new session needed).
            logger.warn("Terminal widget not found for file: ${file.name} (creating placeholder or waiting for service)")
        } else {
            logger.debug("Creating terminal editor for existing widget: ${file.name}")
        }
        
        return OpenCodeTerminalFileEditor(project, file, widget)
    }

    override fun getEditorTypeId(): String = "opencode-terminal-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

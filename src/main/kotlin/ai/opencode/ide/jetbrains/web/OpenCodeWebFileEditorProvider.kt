package ai.opencode.ide.jetbrains.web

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.intellij.openapi.diagnostic.Logger

class OpenCodeWebFileEditorProvider : FileEditorProvider, DumbAware {

    private val logger = Logger.getInstance(OpenCodeWebFileEditorProvider::class.java)

    companion object {
        private val onCloseCallbacks = ConcurrentHashMap<VirtualFile, () -> Unit>()
        private val disposalTasks = ConcurrentHashMap<VirtualFile, ScheduledFuture<*>>()

        fun registerCallback(file: VirtualFile, callback: () -> Unit) {
            cancelDisposal(file)
            onCloseCallbacks[file] = callback
        }

        fun unregisterCallback(file: VirtualFile) {
            onCloseCallbacks.remove(file)
            cancelDisposal(file)
        }

        fun scheduleDisposalCheck(file: VirtualFile, project: Project) {
            cancelDisposal(file)
            
            if (!onCloseCallbacks.containsKey(file)) return

            val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    
                    val isStillOpen = FileEditorManager.getInstance(project).isFileOpen(file)
                    
                    if (!isStillOpen) {
                        onCloseCallbacks.remove(file)?.invoke()
                    }
                }
            }, 5000, TimeUnit.MILLISECONDS)
            
            disposalTasks[file] = task
        }

        private fun cancelDisposal(file: VirtualFile) {
            disposalTasks.remove(file)?.cancel(false)
        }
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val accepted = file is OpenCodeWebVirtualFile && JBCefApp.isSupported()
        if (file is OpenCodeWebVirtualFile) {
            logger.info("accept($file) = $accepted (JBCefSupported=${JBCefApp.isSupported()})")
        }
        return accepted
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        logger.info("createEditor($file)")
        require(file is OpenCodeWebVirtualFile)
        // Cancel disposal if we are re-creating editor (tab move scenario)
        Companion.cancelDisposal(file)
        return OpenCodeWebFileEditor(project, file)
    }

    override fun getEditorTypeId(): String = "opencode-web-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

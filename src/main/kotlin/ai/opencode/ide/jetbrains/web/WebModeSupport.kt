package ai.opencode.ide.jetbrains.web

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.jcef.JBCefApp

import ai.opencode.ide.jetbrains.util.PathUtil

import java.net.URLEncoder

object WebModeSupport {
    
    fun isJcefSupported(): Boolean {
        return try {
            JBCefApp.isSupported()
        } catch (e: Throwable) {
            false
        }
    }

    fun openWebTab(project: Project, host: String, port: Int, password: String? = null, onClose: () -> Unit): OpenCodeWebVirtualFile? {
        if (!isJcefSupported()) {
            Messages.showErrorDialog(project, "JCEF (Embedded Browser) is not supported in this IDE environment.", "Web Interface Error")
            return null
        }

        // Sanitize host for browser connection (0.0.0.0 is not valid for browser navigation)
        val browserHost = if (host == "0.0.0.0") "127.0.0.1" else host
        
        // Use clean URL without credentials.
        // We will inject credentials via JS Pre-Auth in OpenCodeWebFileEditor
        val directoryParam = project.basePath?.let { basePath ->
            val serverPath = PathUtil.toOpenCodeServerPath(basePath)
            URLEncoder.encode(serverPath, "UTF-8")
        }
        val url = if (directoryParam != null) {
            "http://$browserHost:$port/?directory=$directoryParam"
        } else {
            "http://$browserHost:$port/"
        }
        
        val tabName = "OpenCode Web($port)"
        // Pass password to VirtualFile so Editor can use it
        val virtualFile = OpenCodeWebVirtualFile(tabName, url)
        virtualFile.putUserData(OpenCodeWebVirtualFile.PASSWORD_KEY, password)

        OpenCodeWebFileEditorProvider.registerCallback(virtualFile, onClose)

        ApplicationManager.getApplication().invokeLater {
            logger.info("Opening web tab: $url")
            val editors = FileEditorManager.getInstance(project).openFile(virtualFile, true)
            logger.info("FileEditorManager returned ${editors.size} editors")
            if (editors.isEmpty()) {
                Messages.showErrorDialog(project, "Failed to open Web Interface tab. No editor provider found.", "Error")
            }
            pinTab(project, virtualFile)
        }
        
        return virtualFile
    }

    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(WebModeSupport::class.java)
    
    fun unregisterCallback(file: OpenCodeWebVirtualFile) {
        try {
            OpenCodeWebFileEditorProvider.unregisterCallback(file)
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun pinTab(project: Project, file: com.intellij.openapi.vfs.VirtualFile) {
         try {
            val managerEx = FileEditorManagerEx.getInstanceEx(project)
            managerEx.currentWindow?.let { window ->
                if (!window.isFilePinned(file)) {
                    window.setFilePinned(file, true)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

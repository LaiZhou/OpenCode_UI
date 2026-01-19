package ai.opencode.ide.jetbrains.web

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.util.*
import javax.swing.*

class OpenCodeWebFileEditor(
    private val project: Project,
    private val file: OpenCodeWebVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val logger = Logger.getInstance(OpenCodeWebFileEditor::class.java)
    private val browser: JBCefBrowser?
    private val mainPanel = JPanel(BorderLayout())

    init {
        mainPanel.isFocusable = true
        
        var b: JBCefBrowser? = null
        try {
            logger.info("Initializing JBCefBrowser for ${file.targetUrl}")
            b = JBCefBrowser(file.targetUrl)
            
            // 1. Capture JS Console Logs
            b.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int
                ): Boolean {
                    logger.info("[JS-Console] [$level] $message ($source:$line)")
                    return false
                }
            }, b.cefBrowser)

            // 2. Inject Authentication via Header Injection
            val password = file.getUserData(OpenCodeWebVirtualFile.PASSWORD_KEY)
            if (!password.isNullOrBlank()) {
                val authString = "opencode:$password"
                val encodedAuth = Base64.getEncoder().encodeToString(authString.toByteArray())
                val authHeader = "Basic $encodedAuth"
                
                b.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                    override fun getResourceRequestHandler(
                        browser: CefBrowser?,
                        frame: CefFrame?,
                        request: CefRequest?,
                        isNavigation: Boolean,
                        isDownload: Boolean,
                        requestInitiator: String?,
                        disableDefaultHandling: BoolRef?
                    ): CefResourceRequestHandler? {
                        return object : CefResourceRequestHandlerAdapter() {
                            override fun onBeforeResourceLoad(
                                browser: CefBrowser?,
                                frame: CefFrame?,
                                request: CefRequest?
                            ): Boolean {
                                if (request != null && request.url.startsWith(file.targetUrl)) {
                                    val headers = HashMap<String, String>()
                                    request.getHeaderMap(headers)
                                    headers["Authorization"] = authHeader
                                    request.setHeaderMap(headers)
                                    // Change to info so it's visible in logs for verification
                                    logger.info("Injected Auth header for request: ${request.url}")
                                }
                                return false
                            }
                        }
                    }
                }, b.cefBrowser)
            }
            
            // 3. Add Load Handler to catch errors and inject polyfills
            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) {
                        // Inject clipboard polyfill to prevent JS errors in JCEF
                        val polyfillScript = """
                            (function() {
                                if (!navigator.clipboard) {
                                    navigator.clipboard = {};
                                }
                                if (!navigator.clipboard.writeText) {
                                    navigator.clipboard.writeText = function(text) {
                                        console.log('[Polyfill] Clipboard writeText called (not supported in embedded browser)');
                                        return Promise.resolve();
                                    };
                                }
                                if (!navigator.clipboard.readText) {
                                    navigator.clipboard.readText = function() {
                                        console.log('[Polyfill] Clipboard readText called (not supported in embedded browser)');
                                        return Promise.resolve('');
                                    };
                                }
                            })();
                        """.trimIndent()
                        browser?.executeJavaScript(polyfillScript, "polyfill://clipboard", 0)
                        logger.info("Injected clipboard polyfill")
                    }
                }
                
                override fun onLoadError(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?
                ) {
                    if (frame?.isMain == true) {
                        logger.warn("Web Page Load Error: $errorCode - $errorText ($failedUrl)")
                        SwingUtilities.invokeLater {
                             com.intellij.openapi.ui.Messages.showErrorDialog(
                                project,
                                "Failed to load OpenCode Web Interface.\n\nURL: $failedUrl\nError: $errorText ($errorCode)",
                                "Connection Error"
                            )
                        }
                    }
                }
            }, b.cefBrowser)

            mainPanel.add(b.component, BorderLayout.CENTER)
            Disposer.register(this, b)
            
        } catch (e: Throwable) {
            logger.error("Failed to initialize JBCefBrowser", e)
            val errorLabel = JLabel("<html><center><h3>Web View Initialization Error</h3><p>${e.message}</p></center></html>")
            errorLabel.horizontalAlignment = SwingConstants.CENTER
            mainPanel.add(errorLabel, BorderLayout.CENTER)
        }
        browser = b
    }

    override fun getComponent(): JComponent = mainPanel

    override fun getPreferredFocusedComponent(): JComponent? = browser?.component ?: mainPanel

    override fun getName(): String = "OpenCode Web"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): com.intellij.openapi.fileEditor.FileEditorLocation? = null

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        OpenCodeWebFileEditorProvider.scheduleDisposalCheck(file, project)
    }
}

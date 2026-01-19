package ai.opencode.ide.jetbrains.ui

import ai.opencode.ide.jetbrains.web.WebModeSupport
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.util.Base64
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for configuring OpenCode server connection.
 * Allows user to specify host:port before starting the terminal.
 */
class OpenCodeConnectDialog(
    private val project: Project,
    private val defaultPort: Int
) : DialogWrapper(project, true) {

    data class ConnectionInfo(
        val hostname: String,
        val port: Int,
        val password: String?,
        val useWebInterface: Boolean
    )

    private val addressField = JBTextField("127.0.0.1:$defaultPort")
    private val passwordField = JBPasswordField()
    private val webInterfaceCheckBox = JBCheckBox("Use Web Interface")
    
    var hostname: String = "127.0.0.1"
        private set
    var port: Int = defaultPort
        private set
    var password: String? = null
        private set
    var useWebInterface: Boolean = false
        private set

    init {
        title = "Connect to OpenCode"
        setOKButtonText("Connect")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(300), JBUI.scale(140))

        val formPanel = JPanel(GridLayout(5, 1, 0, JBUI.scale(4)))
        val addressLabel = JBLabel("Server address:")
        val passwordLabel = JBLabel("Server password (optional):")

        // Load saved values
        val props = PropertiesComponent.getInstance()
        val savedAddress = props.getValue(PROP_LAST_ADDRESS, "127.0.0.1:$defaultPort")
        addressField.text = savedAddress
        
        val savedUseWeb = props.getBoolean(PROP_USE_WEB_INTERFACE, false)
        webInterfaceCheckBox.isSelected = savedUseWeb
        
        // Load saved password (Base64 encoded for basic obfuscation)
        try {
            val encodedPassword = props.getValue(PROP_LAST_PASSWORD, "")
            if (encodedPassword.isNotBlank()) {
                val decodedPassword = String(Base64.getDecoder().decode(encodedPassword))
                passwordField.text = decodedPassword
            }
        } catch (e: Exception) {
            // Ignore if password retrieval fails
        }

        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096)"
        passwordField.toolTipText = "OPENCODE_SERVER_PASSWORD"
        passwordField.emptyText.text = "For remote OpenCode servers"
        webInterfaceCheckBox.toolTipText = "Open internal browser instead of terminal"
        
        if (!WebModeSupport.isJcefSupported()) {
            webInterfaceCheckBox.isEnabled = false
            webInterfaceCheckBox.toolTipText = "Web interface is not supported in this IDE environment"
        }

        formPanel.add(addressLabel)
        formPanel.add(addressField)
        formPanel.add(passwordLabel)
        formPanel.add(passwordField)
        formPanel.add(webInterfaceCheckBox)

        panel.add(formPanel, BorderLayout.CENTER)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = addressField

    override fun doValidate(): ValidationInfo? {
        val input = addressField.text.trim()
        
        if (input.isBlank()) {
            return ValidationInfo("Server address cannot be empty", addressField)
        }
        
        val parts = input.split(":")
        if (parts.size != 2) {
            return ValidationInfo("Invalid format. Expected: hostname:port", addressField)
        }
        
        val host = parts[0].trim()
        val portStr = parts[1].trim()
        
        if (host.isBlank()) {
            return ValidationInfo("Hostname cannot be empty", addressField)
        }
        
        val portNum = portStr.toIntOrNull()
        if (portNum == null || portNum < 1 || portNum > 65535) {
            return ValidationInfo("Port must be a number between 1 and 65535", addressField)
        }
        
        return null
    }

    override fun doOKAction() {
        val input = addressField.text.trim()
        val parts = input.split(":")
        hostname = parts[0].trim()
        port = parts[1].trim().toInt()

        val passwordValue = passwordField.password.concatToString().trim()
        password = passwordValue.ifBlank { null }
        
        useWebInterface = webInterfaceCheckBox.isSelected

        // Save values for next time
        val props = PropertiesComponent.getInstance()
        props.setValue(PROP_LAST_ADDRESS, "$hostname:$port")
        props.setValue(PROP_USE_WEB_INTERFACE, useWebInterface)
        
        // Save password (Base64 encoded for basic obfuscation)
        try {
            if (!password.isNullOrBlank()) {
                val encodedPassword = Base64.getEncoder().encodeToString(password!!.toByteArray())
                props.setValue(PROP_LAST_PASSWORD, encodedPassword)
            } else {
                // Clear saved password if empty
                props.unsetValue(PROP_LAST_PASSWORD)
            }
        } catch (e: Exception) {
            // Ignore if password save fails
        }

        super.doOKAction()
    }

    companion object {
        private const val PROP_LAST_ADDRESS = "opencode.lastAddress"
        private const val PROP_LAST_PASSWORD = "opencode.lastPassword"
        private const val PROP_USE_WEB_INTERFACE = "opencode.useWebInterface"
        
        /**
         * Shows the dialog and returns the result.
         * @return ConnectionInfo if user clicked Connect, null if cancelled
         */
        fun show(project: Project, defaultPort: Int): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, defaultPort)
            return if (dialog.showAndGet()) {
                ConnectionInfo(dialog.hostname, dialog.port, dialog.password, dialog.useWebInterface)
            } else {
                null
            }
        }
    }
}

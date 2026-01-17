package ai.opencode.ide.jetbrains.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
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
        val password: String?
    )

    private val addressField = JBTextField("0.0.0.0:$defaultPort")
    private val passwordField = JBPasswordField()
    
    var hostname: String = "0.0.0.0"
        private set
    var port: Int = defaultPort
        private set
    var password: String? = null
        private set

    init {
        title = "Connect to OpenCode"
        setOKButtonText("Connect")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(300), JBUI.scale(110))

        val formPanel = JPanel(GridLayout(4, 1, 0, JBUI.scale(4)))
        val addressLabel = JBLabel("Server address:")
        val passwordLabel = JBLabel("Server password (optional):")

        addressField.toolTipText = "Format: hostname:port (e.g., 0.0.0.0:4096)"
        passwordField.toolTipText = "OPENCODE_SERVER_PASSWORD"
        passwordField.emptyText.text = "For remote OpenCode servers"

        formPanel.add(addressLabel)
        formPanel.add(addressField)
        formPanel.add(passwordLabel)
        formPanel.add(passwordField)

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

        super.doOKAction()
    }

    companion object {
        /**
         * Shows the dialog and returns the result.
         * @return ConnectionInfo if user clicked Connect, null if cancelled
         */
        fun show(project: Project, defaultPort: Int): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, defaultPort)
            return if (dialog.showAndGet()) {
                ConnectionInfo(dialog.hostname, dialog.port, dialog.password)
            } else {
                null
            }
        }
    }
}

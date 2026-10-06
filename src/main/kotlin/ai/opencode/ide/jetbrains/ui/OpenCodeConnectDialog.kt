package ai.opencode.ide.jetbrains.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.web.WebModeSupport
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.util.Base64
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for configuring OpenCode server connection.
 * Allows user to specify host:port before starting the terminal.
 * @param startNewPort Default port for START_NEW action (first free port)
 * @param attachPort Default port for AUTO/ATTACH actions (highest running server, null if none).
 *        When null, AUTO falls back to [startNewPort] (it spawns there), while ATTACH falls back
 *        to the last used address, never to a free port.
 */
class OpenCodeConnectDialog(
    private val project: Project,
    private val startNewPort: Int,
    private val attachPort: Int?
) : DialogWrapper(project, true) {

    data class ConnectionInfo(
        val hostname: String,
        val port: Int,
        val password: String?,
        val action: OpenCodeService.ConnectAction,
        val ui: OpenCodeService.ConnectionMode,
        val customBasePath: String? = null
    )

    private val addressField = JBTextField("127.0.0.1:$startNewPort")
    private val passwordField = JBPasswordField()
    private val basePathField = ComboBox<String>().apply {
        isEditable = true
    }
    private val actionCombo = JComboBox<OpenCodeService.ConnectAction>().apply {
        OpenCodeService.ConnectAction.values().forEach { addItem(it) }
        selectedItem = OpenCodeService.ConnectAction.AUTO
    }
    private val interfaceCombo = JComboBox<OpenCodeService.ConnectionMode>().apply {
        OpenCodeService.ConnectionMode.values().filter { it != OpenCodeService.ConnectionMode.NONE }.forEach { addItem(it) }
        selectedItem = OpenCodeService.ConnectionMode.TERMINAL
    }

    private var lastSuggestion: String? = null
    private var savedLastAddress: String? = null

    var hostname: String = "127.0.0.1"
        private set
    var port: Int = startNewPort
        private set
    var password: String? = null
        private set
    var customBasePath: String? = null
        private set

    init {
        title = "Connect to OpenCode"
        setOKButtonText("Connect")
        setCancelButtonText("Close")
        init()

        loadSavedValues()
        refreshInterfaceOptions()
        actionCombo.addActionListener {
            refreshInterfaceOptions()
            refreshSuggestedAddress()
        }
    }

    private fun suggestedAddress(action: OpenCodeService.ConnectAction): String {
        return when (action) {
            OpenCodeService.ConnectAction.START_NEW -> "127.0.0.1:$startNewPort"
            // AUTO spawns a server on the suggested port when none is running, so a free port is a valid fallback
            OpenCodeService.ConnectAction.AUTO -> "127.0.0.1:${attachPort ?: startNewPort}"
            // ATTACH never suggests a free port: fall back to the last used address instead
            OpenCodeService.ConnectAction.ATTACH -> attachPort?.let { "127.0.0.1:$it" } ?: savedLastAddress.orEmpty()
        }
    }

    /** Updates the address field to the suggestion for the current action, unless the user typed a custom address */
    private fun refreshSuggestedAddress(force: Boolean = false) {
        val suggestion = suggestedAddress(actionCombo.selectedItem as OpenCodeService.ConnectAction)
        val current = addressField.text.trim()
        if (force || current.isBlank() || current == lastSuggestion) {
            addressField.text = suggestion
            lastSuggestion = suggestion
        }
    }

    private fun loadSavedValues() {
        val props = PropertiesComponent.getInstance()

        // Last successfully used address - fallback suggestion for ATTACH when no server was detected
        savedLastAddress = props.getValue(PROP_LAST_ADDRESS, "").trim().ifBlank { null }

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

        // Load saved custom base path
        val savedPath = props.getValue(PROP_CUSTOM_BASE_PATH, "")

        // Populate module paths
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules
        val paths = modules.mapNotNull { it.guessModuleDir()?.path }.sorted()
        paths.forEach { basePathField.addItem(it) }

        if (savedPath.isNotBlank()) {
            basePathField.item = savedPath
        } else {
            basePathField.selectedIndex = -1
        }

        val savedAction = props.getInt(PROP_CONNECT_ACTION, OpenCodeService.ConnectAction.AUTO.ordinal)
        val savedInterface = props.getInt(PROP_INTERFACE_CHOICE, OpenCodeService.ConnectionMode.TERMINAL.ordinal)
        actionCombo.selectedIndex = savedAction
        interfaceCombo.selectedIndex = savedInterface

        // Pre-fill address according to the initially selected action
        refreshSuggestedAddress(force = true)
    }

    private fun refreshInterfaceOptions() {
        val action = actionCombo.selectedItem as OpenCodeService.ConnectAction
        val isWebSupported = WebModeSupport.isJcefSupported()

        val allowed = OpenCodeService.ConnectionMode.values()
            .filter { it != OpenCodeService.ConnectionMode.NONE }
            .toMutableList()

        if (!isWebSupported) allowed.remove(OpenCodeService.ConnectionMode.WEB)
        if (action == OpenCodeService.ConnectAction.START_NEW) allowed.remove(OpenCodeService.ConnectionMode.HEADLESS)

        interfaceCombo.removeAllItems()
        for (choice in allowed) {
            interfaceCombo.addItem(choice)
        }

        val current = interfaceCombo.selectedItem as OpenCodeService.ConnectionMode
        val currentIndex = allowed.indexOf(current)
        interfaceCombo.selectedIndex = if (currentIndex >= 0) currentIndex else 0
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(400), JBUI.scale(280))

        val formPanel = JPanel(GridLayout(8, 1, 0, JBUI.scale(4)))
        val addressLabel = JBLabel("Server address:")
        val passwordLabel = JBLabel("Server password (optional):")
        val basePathLabel = JBLabel("Custom base path (optional):")
        val actionLabel = JBLabel("Action:")
        val interfaceLabel = JBLabel("Interface:")

        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096)"
        passwordField.toolTipText = "OPENCODE_SERVER_PASSWORD"
        passwordField.emptyText.text = "For remote OpenCode servers"
        basePathField.toolTipText = "Override project base path for opencode.exe working directory"
        actionLabel.toolTipText = "AUTO: Try to connect or spawn a new server. START_NEW: Spawn a new server on localhost. ATTACH: Connect to an existing server."
        interfaceLabel.toolTipText = "TERMINAL: Open in terminal UI. WEB: Open in embedded browser. HEADLESS: Silent connection for external terminal."
        actionCombo.toolTipText = actionLabel.toolTipText
        interfaceCombo.toolTipText = interfaceLabel.toolTipText

        formPanel.add(addressLabel)
        formPanel.add(addressField)
        formPanel.add(passwordLabel)
        formPanel.add(passwordField)
        formPanel.add(basePathLabel)
        formPanel.add(basePathField)
        formPanel.add(actionLabel)
        formPanel.add(actionCombo)
        formPanel.add(interfaceLabel)
        formPanel.add(interfaceCombo)

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

        val basePathValue = (basePathField.editor.item as? String)?.trim() ?: ""
        customBasePath = basePathValue.ifBlank { null }

        val action = actionCombo.selectedItem as OpenCodeService.ConnectAction
        val ui = interfaceCombo.selectedItem as OpenCodeService.ConnectionMode

        // Save values for next time
        val props = PropertiesComponent.getInstance()
        props.setValue(PROP_LAST_ADDRESS, "$hostname:$port")

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

        // Save custom base path
        if (!customBasePath.isNullOrBlank()) {
            props.setValue(PROP_CUSTOM_BASE_PATH, customBasePath!!)
        } else {
            props.unsetValue(PROP_CUSTOM_BASE_PATH)
        }

        props.setValue(PROP_CONNECT_ACTION, action.ordinal.toString())
        props.setValue(PROP_INTERFACE_CHOICE, ui.ordinal.toString())

        super.doOKAction()
    }

    companion object {
        private const val PROP_LAST_ADDRESS = "opencode.lastAddress"
        private const val PROP_LAST_PASSWORD = "opencode.lastPassword"
        private const val PROP_CUSTOM_BASE_PATH = "opencode.customBasePath"
        private const val PROP_CONNECT_ACTION = "opencode.action"
        private const val PROP_INTERFACE_CHOICE = "opencode.interface"

        /**
         * Shows the dialog and returns the result.
         * @param startNewPort Suggested port for the START_NEW action (first free port)
         * @param attachPort Suggested port for AUTO/ATTACH actions (highest running server, null if none)
         * @return ConnectionInfo if user clicked Connect, null if cancelled
         */
        fun show(project: Project, startNewPort: Int, attachPort: Int?): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, startNewPort, attachPort)
            return if (dialog.showAndGet()) {
                ConnectionInfo(dialog.hostname, dialog.port, dialog.password, dialog.actionCombo.selectedItem as OpenCodeService.ConnectAction, dialog.interfaceCombo.selectedItem as OpenCodeService.ConnectionMode, dialog.customBasePath)
            } else {
                null
            }
        }
    }
}

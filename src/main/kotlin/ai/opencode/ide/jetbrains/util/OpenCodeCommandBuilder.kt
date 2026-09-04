package ai.opencode.ide.jetbrains.util

/**
 * Builds the shell command used to run the OpenCode CLI inside an IDE terminal.
 */
object OpenCodeCommandBuilder {

    /** `0.0.0.0` is a valid listen address but is not connectable; clients must use loopback. */
    fun connectableHost(hostname: String): String {
        val h = hostname.trim()
        return if (h.isEmpty() || h == "0.0.0.0" || h == "::" || h == "[::]") "127.0.0.1" else h
    }

    fun serverUrl(hostname: String, port: Int): String = "http://${connectableHost(hostname)}:$port"

    /**
     * @param command path of the OpenCode binary (quoted here if it contains spaces)
     * @param attach `true` to attach to an existing server, `false` to start a new one
     * @param cont pass `--continue` to resume the last session
     * @param password value for `OPENCODE_SERVER_PASSWORD`, exported for this command only
     */
    fun build(
        command: String,
        hostname: String,
        port: Int,
        password: String?,
        cont: Boolean,
        attach: Boolean,
        windows: Boolean = isWindows()
    ): String {
        // Quote command if it contains spaces (e.g. absolute path on Windows)
        val cmdSafe = if (command.contains(" ")) "\"$command\"" else command
        val args = if (attach) "attach ${serverUrl(hostname, port)}" else "--hostname $hostname --port $port"
        val base = "$cmdSafe $args${if (cont) " --continue" else ""}"
        if (password.isNullOrBlank()) return base
        return if (windows) {
            "cmd /c \"set \"OPENCODE_SERVER_PASSWORD=${password.replace("\"", "\\\"")}\" && $base\""
        } else {
            "OPENCODE_SERVER_PASSWORD='${password.replace("'", "'\\''")}' $base"
        }
    }

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("windows")
}

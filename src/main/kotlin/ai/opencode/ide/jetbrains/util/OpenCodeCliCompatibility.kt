package ai.opencode.ide.jetbrains.util

/**
 * Detects which command-line syntax the installed OpenCode CLI speaks and builds the matching
 * command lines.
 *
 * OpenCode v1 accepts `--hostname`/`--port` on the root command and runs the server as part of the
 * TUI process. OpenCode v2 removed those root flags: `opencode serve --hostname H --port P` starts
 * the server, and `opencode --server <url>` attaches a TUI to it. Passing the v1 flags to a v2
 * binary makes the CLI exit with "Unrecognized flag", which is what issue #35 reports.
 */
internal object OpenCodeCliCompatibility {

    /** Which CLI syntax to generate. */
    enum class Mode { LEGACY, V2 }

    private val VERSION_PATTERN = Regex("""(?:^|\D)v?(\d+)\.\d+\.\d+""")
    private val LEGACY_HOSTNAME_FLAG = Regex("""(?m)^\s*--hostname\b""")
    private val LEGACY_PORT_FLAG = Regex("""(?m)^\s*--port\b""")

    /**
     * Reads the CLI major version from `opencode --version` output.
     *
     * @param output combined stdout/stderr of the version command
     * @return [Mode.V2] for major version 2 or newer, [Mode.LEGACY] for 0.x/1.x, null when the output
     * does not contain a version (for example development builds)
     */
    fun modeFromVersionOutput(output: String): Mode? {
        val major = VERSION_PATTERN.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (major >= 2) Mode.V2 else Mode.LEGACY
    }

    /**
     * Fallback detection when `--version` prints no parseable version: only v1 advertises the
     * `--hostname`/`--port` root flags, so their absence means the binary speaks v2 syntax.
     */
    fun modeFromHelpOutput(output: String): Mode {
        val hasLegacyServerFlags = LEGACY_HOSTNAME_FLAG.containsMatchIn(output) &&
            LEGACY_PORT_FLAG.containsMatchIn(output)
        return if (hasLegacyServerFlags) Mode.LEGACY else Mode.V2
    }

    /** Host other clients can connect to; `0.0.0.0` is valid to listen on but not to connect to. */
    fun connectHost(host: String): String = if (host == "0.0.0.0") "127.0.0.1" else host

    /** Server URL used by v2 clients (`--server`). */
    fun serverUrl(host: String, port: Int): String = "http://${connectHost(host)}:$port"

    /** Arguments for `opencode serve`, which starts the v2 API and web server. */
    fun serverArguments(host: String, port: Int): List<String> =
        listOf("serve", "--hostname", host, "--port", port.toString())

    /**
     * Builds the command typed into the embedded terminal to start/attach the TUI.
     *
     * @param command executable path or bare binary name
     * @param host listen/connect host
     * @param port server port
     * @param continueSession pass `--continue` to resume the most recent session
     * @param mode CLI syntax to generate
     */
    fun terminalCommand(
        command: String,
        host: String,
        port: Int,
        continueSession: Boolean,
        mode: Mode
    ): String {
        // Quote the executable when its path contains spaces (e.g. an absolute Windows path).
        val executable = if (command.contains(' ')) "\"$command\"" else command
        val base = when (mode) {
            Mode.LEGACY -> "$executable --hostname $host --port $port"
            Mode.V2 -> "$executable --server ${serverUrl(host, port)}"
        }
        return if (continueSession) "$base --continue" else base
    }

    /**
     * Prefixes a terminal command with the server password environment variables.
     *
     * v2 reads `OPENCODE_PASSWORD` and still honours the v1 name `OPENCODE_SERVER_PASSWORD`, so both
     * are exported; the same variables are also read by a v2 client attaching to an explicit
     * `--server`.
     */
    fun withPassword(command: String, password: String, isWindows: Boolean): String {
        if (password.isBlank()) return command
        return if (isWindows) {
            val quoted = password.replace("\"", "\\\"")
            "cmd /c \"set \"OPENCODE_PASSWORD=$quoted\" && set \"OPENCODE_SERVER_PASSWORD=$quoted\" && $command\""
        } else {
            val quoted = password.replace("'", "'\\''")
            "OPENCODE_PASSWORD='$quoted' OPENCODE_SERVER_PASSWORD='$quoted' $command"
        }
    }

    /**
     * Arguments used to probe an already running server's health.
     *
     * v1 exposes `/global/health`; v2 has no health route, but `/api/session` answers with an
     * authentication or validation status as soon as the server is listening.
     */
    fun healthProbePaths(): List<String> = listOf("/global/health", "/api/session")
}

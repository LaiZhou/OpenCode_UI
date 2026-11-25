package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeCliCompatibilityTest {

    @Test
    fun `reads the CLI major version`() {
        assertEquals(OpenCodeCliCompatibility.Mode.LEGACY, OpenCodeCliCompatibility.modeFromVersionOutput("0.4.2"))
        assertEquals(OpenCodeCliCompatibility.Mode.LEGACY, OpenCodeCliCompatibility.modeFromVersionOutput("opencode 1.12.0"))
        assertEquals(OpenCodeCliCompatibility.Mode.V2, OpenCodeCliCompatibility.modeFromVersionOutput("2.0.0"))
        assertEquals(OpenCodeCliCompatibility.Mode.V2, OpenCodeCliCompatibility.modeFromVersionOutput("OpenCode v2.3.1\n"))
    }

    @Test
    fun `returns null when the version output is not parseable`() {
        assertNull(OpenCodeCliCompatibility.modeFromVersionOutput("OpenCode development build"))
        assertNull(OpenCodeCliCompatibility.modeFromVersionOutput(""))
    }

    @Test
    fun `detects legacy syntax from the root help flags`() {
        val legacyHelp = """
            USAGE
              opencode [project]

            FLAGS
              --port number        Port to listen on
              --hostname string    Hostname to listen on
        """.trimIndent()

        assertEquals(OpenCodeCliCompatibility.Mode.LEGACY, OpenCodeCliCompatibility.modeFromHelpOutput(legacyHelp))
    }

    @Test
    fun `detects v2 syntax when the root help has no server flags`() {
        val v2Help = """
            USAGE
              opencode <subcommand> [flags] [<directory>]

            FLAGS
              --server string    Connect to a server URL instead of the background service

            SUBCOMMANDS
              serve    Start the v2 API and web server
        """.trimIndent()

        assertEquals(OpenCodeCliCompatibility.Mode.V2, OpenCodeCliCompatibility.modeFromHelpOutput(v2Help))
    }

    @Test
    fun `builds the legacy terminal command`() {
        assertEquals(
            "opencode --hostname 127.0.0.1 --port 4096 --continue",
            OpenCodeCliCompatibility.terminalCommand(
                "opencode", "127.0.0.1", 4096, continueSession = true,
                mode = OpenCodeCliCompatibility.Mode.LEGACY
            )
        )
    }

    @Test
    fun `builds the v2 terminal command with a server URL`() {
        assertEquals(
            "opencode --server http://127.0.0.1:4096 --continue",
            OpenCodeCliCompatibility.terminalCommand(
                "opencode", "0.0.0.0", 4096, continueSession = true,
                mode = OpenCodeCliCompatibility.Mode.V2
            )
        )
    }

    @Test
    fun `quotes executable paths containing spaces`() {
        assertEquals(
            "\"C:/Program Files/OpenCode/opencode.exe\" --server http://127.0.0.1:4096",
            OpenCodeCliCompatibility.terminalCommand(
                "C:/Program Files/OpenCode/opencode.exe", "127.0.0.1", 4096, continueSession = false,
                mode = OpenCodeCliCompatibility.Mode.V2
            )
        )
    }

    @Test
    fun `builds the v2 serve arguments`() {
        assertEquals(
            listOf("serve", "--hostname", "127.0.0.1", "--port", "4096"),
            OpenCodeCliCompatibility.serverArguments("127.0.0.1", 4096)
        )
    }

    @Test
    fun `maps wildcard listen hosts to a connectable host`() {
        assertEquals("127.0.0.1", OpenCodeCliCompatibility.connectHost("0.0.0.0"))
        assertEquals("localhost", OpenCodeCliCompatibility.connectHost("localhost"))
        assertEquals("http://127.0.0.1:4096", OpenCodeCliCompatibility.serverUrl("0.0.0.0", 4096))
    }

    @Test
    fun `prefixes the password environment for posix shells`() {
        assertEquals(
            "OPENCODE_PASSWORD='a'\\''b' OPENCODE_SERVER_PASSWORD='a'\\''b' opencode --server http://127.0.0.1:4096",
            OpenCodeCliCompatibility.withPassword(
                "opencode --server http://127.0.0.1:4096", "a'b", isWindows = false
            )
        )
    }

    @Test
    fun `leaves the command untouched without a password`() {
        assertEquals(
            "opencode --continue",
            OpenCodeCliCompatibility.withPassword("opencode --continue", "", isWindows = false)
        )
    }

    @Test
    fun `probes legacy health first and the v2 session route as fallback`() {
        val paths = OpenCodeCliCompatibility.healthProbePaths()
        assertEquals("/global/health", paths.first())
        assertTrue(paths.contains("/api/session"))
        assertFalse(paths.isEmpty())
    }
}

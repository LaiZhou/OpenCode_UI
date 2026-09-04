package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeCommandBuilderTest {

    @Test
    fun spawnUsesHostnameAndPortFlags() {
        val cmd = OpenCodeCommandBuilder.build(
            "opencode", "127.0.0.1", 4096, null, cont = true, attach = false, windows = false
        )
        assertEquals("opencode --hostname 127.0.0.1 --port 4096 --continue", cmd)
    }

    @Test
    fun attachUsesAttachSubcommandWithUrl() {
        val cmd = OpenCodeCommandBuilder.build(
            "opencode", "192.0.2.10", 4096, null, cont = false, attach = true, windows = false
        )
        assertEquals("opencode attach http://192.0.2.10:4096", cmd)
    }

    @Test
    fun attachRewritesWildcardHostToLoopback() {
        val cmd = OpenCodeCommandBuilder.build(
            "opencode", "0.0.0.0", 4096, null, cont = true, attach = true, windows = false
        )
        assertEquals("opencode attach http://127.0.0.1:4096 --continue", cmd)
    }

    @Test
    fun passwordIsExportedAndQuoted() {
        val cmd = OpenCodeCommandBuilder.build(
            "opencode", "10.0.0.5", 4096, "pa'ss", cont = false, attach = true, windows = false
        )
        assertEquals("OPENCODE_SERVER_PASSWORD='pa'\\''ss' opencode attach http://10.0.0.5:4096", cmd)
    }

    @Test
    fun windowsPasswordUsesCmdSet() {
        val cmd = OpenCodeCommandBuilder.build(
            "C:\\Program Files\\opencode\\opencode.exe", "10.0.0.5", 4096, "pw",
            cont = false, attach = true, windows = true
        )
        assertEquals(
            "cmd /c \"set \"OPENCODE_SERVER_PASSWORD=pw\" && " +
                "\"C:\\Program Files\\opencode\\opencode.exe\" attach http://10.0.0.5:4096\"",
            cmd
        )
    }

    @Test
    fun binaryPathWithSpacesIsQuoted() {
        val cmd = OpenCodeCommandBuilder.build(
            "/opt/my tools/opencode", "127.0.0.1", 4096, null, cont = false, attach = false, windows = false
        )
        assertEquals("\"/opt/my tools/opencode\" --hostname 127.0.0.1 --port 4096", cmd)
    }
}

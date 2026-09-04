package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    private fun ok(input: String): ServerAddress.Parsed {
        val result = ServerAddress.parse(input)
        assertTrue("expected $input to parse, got $result", result is ServerAddress.Result.Ok)
        return (result as ServerAddress.Result.Ok).address
    }

    private fun error(input: String): String {
        val result = ServerAddress.parse(input)
        assertTrue("expected $input to fail", result is ServerAddress.Result.Error)
        return (result as ServerAddress.Result.Error).message
    }

    @Test
    fun parsesHostAndPort() {
        assertEquals(ServerAddress.Parsed("127.0.0.1", 4096), ok(" 127.0.0.1:4096 "))
    }

    @Test
    fun stripsHttpSchemeAndPath() {
        assertEquals(ServerAddress.Parsed("192.0.2.10", 4096), ok("http://192.0.2.10:4096"))
        assertEquals(ServerAddress.Parsed("192.0.2.10", 4096), ok("HTTP://192.0.2.10:4096/"))
    }

    @Test
    fun rejectsHttpsWithAnExplicitMessage() {
        assertTrue(error("https://example.com:4096").contains("HTTPS"))
    }

    @Test
    fun rejectsMissingOrInvalidPort() {
        assertTrue(error("127.0.0.1").contains("hostname:port"))
        assertTrue(error("127.0.0.1:0").contains("between 1 and 65535"))
        assertTrue(error("127.0.0.1:abc").contains("between 1 and 65535"))
        assertTrue(error("   ").contains("empty"))
        assertTrue(error(":4096").contains("Hostname"))
    }
}

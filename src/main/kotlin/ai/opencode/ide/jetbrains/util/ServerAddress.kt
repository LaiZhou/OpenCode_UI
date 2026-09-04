package ai.opencode.ide.jetbrains.util

/**
 * Parses the `host:port` string entered in the connection dialog.
 */
object ServerAddress {

    data class Parsed(val hostname: String, val port: Int)

    sealed class Result {
        data class Ok(val address: Parsed) : Result()
        data class Error(val message: String) : Result()
    }

    fun parse(input: String): Result {
        var raw = input.trim()
        if (raw.isEmpty()) return Result.Error("Server address cannot be empty")

        if (raw.startsWith("https://", ignoreCase = true)) {
            return Result.Error("HTTPS servers are not supported yet. Use http://host:port")
        }
        if (raw.startsWith("http://", ignoreCase = true)) raw = raw.substring("http://".length)
        raw = raw.substringBefore('/').trim()

        val parts = raw.split(":")
        if (parts.size != 2) return Result.Error("Invalid format. Expected: hostname:port")

        val host = parts[0].trim()
        if (host.isBlank()) return Result.Error("Hostname cannot be empty")

        val port = parts[1].trim().toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            return Result.Error("Port must be a number between 1 and 65535")
        }
        return Result.Ok(Parsed(host, port))
    }
}

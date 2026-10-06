package ai.opencode.ide.jetbrains.util

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.Proxy
import java.net.ServerSocket

/**
 * Utility for finding available ports for OpenCode server.
 * 
 * Port allocation strategy:
 * - Starts from port 4096 and increments until an available port is found
 * - Once a port is allocated, it is fixed for the lifetime of that session
 * - Server detection scans the same range [START_PORT, RANGE_END) downwards and
 *   returns the highest occupied port
 */
object PortFinder {
    private val logger = Logger.getInstance(PortFinder::class.java)
    private const val START_PORT = 4096
    private const val MAX_ATTEMPTS = 100

    /** Exclusive upper bound shared by port allocation and server detection. */
    private const val RANGE_END = START_PORT + MAX_ATTEMPTS
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    /**
     * Finds an available port starting from START_PORT.
     * Checks both that the port is not bound AND that no OpenCode server is running on it.
     * @return An available port number
     * @throws IOException if no available port is found within MAX_ATTEMPTS
     */
    fun findAvailablePort(): Int {
        logger.info("Searching for available port starting from $START_PORT")
        for (port in START_PORT until RANGE_END) {
            if (isPortAvailable(port)) {
                logger.info("Found available port: $port")
                return port
            }
            logger.debug("Port $port is not available")
        }
        throw IOException("No available port found in range $START_PORT-${RANGE_END - 1}")
    }

    /**
     * Checks if a specific port is available for use (not bound by any process).
     * Checks availability by:
     * 1. Attempting to connect to 127.0.0.1 (If successful, it's occupied)
     * 2. Attempting to bind specifically to 127.0.0.1 (Explicit IPv4 check)
     */
    fun isPortAvailable(port: Int): Boolean {
        // Check 0: Try to connect to 127.0.0.1. If we can connect, someone is listening.
        if (isPortOpen("127.0.0.1", port, timeoutMs = 100)) {
            return false
        }

        // Check 1: Explicit IPv4 localhost bind check
        return try {
            java.net.ServerSocket().use { s ->
                s.reuseAddress = false
                s.bind(java.net.InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Checks if OpenCode server is running and healthy on the specified port.
     * Uses Proxy.NO_PROXY to bypass system proxy settings.
     * Supports HTTP Basic Authentication if credentials are provided.
     */
    fun isOpenCodeRunningOnPort(
        port: Int, 
        hostname: String = "127.0.0.1",
        username: String? = null,
        password: String? = null
    ): Boolean {
        return try {
            val url = java.net.URI("http://$hostname:$port/global/health").toURL()
            val connection = url.openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            
            if (username != null && password != null) {
                val credentials = "$username:$password"
                val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
            }
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            val isHealthy = responseCode == 200 || responseCode == 401 || responseCode == 403
            if (!isHealthy) {
                logger.debug("Health check failed for $hostname:$port. Response code: $responseCode")
            }
            isHealthy
        } catch (e: Exception) {
            logger.debug("Health check failed for $hostname:$port. Error: ${e.message}")
            false
        }
    }

    /**
     * Scans for a running OpenCode server in the port range [START_PORT, RANGE_END),
     * which is exactly the range [findAvailablePort] allocates from.
     * Scans downwards so that the highest occupied port is returned first.
     * @param healthCheck Predicate deciding whether a port hosts a healthy OpenCode server
     * @return The highest port with a running server, null otherwise
     */
    fun findLastRunningOpenCodeServer(healthCheck: (Int) -> Boolean = { isOpenCodeRunningOnPort(it) }): Int? {
        for (port in RANGE_END - 1 downTo START_PORT) {
            if (healthCheck(port)) {
                logger.info("Found running OpenCode server on port: $port")
                return port
            }
        }
        return null
    }

    /**
     * Waits for the specified port to be bound and accepting connections.
     * @param port The port to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if port became available, false if timed out
     */
    fun waitForPort(
        port: Int,
        host: String = "127.0.0.1",
        timeoutMs: Long = 10000,
        requireHealth: Boolean = true
    ): Boolean {
        logger.info("Waiting for port $host:$port (timeout=${timeoutMs}ms, requireHealth=$requireHealth)")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val ready = if (requireHealth) {
                // Important: Health check implies HTTP connectivity and OpenCode readiness.
                isOpenCodeRunningOnPort(port, host)
            } else {
                isPortOpen(host, port)
            }

            if (ready) {
                logger.info("Port $host:$port is ready.")
                return true
            }
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        logger.warn("Timed out waiting for port $host:$port after ${timeoutMs}ms")
        return false
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 1000): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

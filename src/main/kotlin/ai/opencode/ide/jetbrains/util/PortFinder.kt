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
 * - No scanning for existing servers - each project gets its own server instance
 */
object PortFinder {
    private val logger = Logger.getInstance(PortFinder::class.java)
    private const val START_PORT = 4096
    private const val MAX_ATTEMPTS = 100
    private const val CONNECT_TIMEOUT_MS = 2000
    private const val READ_TIMEOUT_MS = 2000

    /**
     * Finds an available port starting from START_PORT.
     * Checks both that the port is not bound AND that no OpenCode server is running on it.
     * @return An available port number
     * @throws IOException if no available port is found within MAX_ATTEMPTS
     */
    fun findAvailablePort(): Int {
        logger.info("Searching for available port starting from $START_PORT")
        for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
            val portAvailable = isPortAvailable(port)
            val openCodeRunning = isOpenCodeRunningOnPort(port)
            
            if (!portAvailable) {
                logger.debug("Port $port is not available (already bound)")
                continue
            }
            
            if (openCodeRunning) {
                logger.debug("Port $port has OpenCode server already running")
                continue
            }
            
            logger.info("Found available port: $port")
            return port
        }
        throw IOException("No available port found in range $START_PORT-${START_PORT + MAX_ATTEMPTS - 1}")
    }

    /**
     * Checks if a specific port is available for use (not bound by any process).
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
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
            val url = java.net.URL("http://$hostname:$port/global/health")
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
            responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Scans for a running OpenCode server in the port range.
     * @return The port number if a running server is found, null otherwise
     */
    fun findRunningOpenCodeServer(): Int? {
        for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
            if (isOpenCodeRunningOnPort(port)) {
                return port
            }
        }
        return null
    }
}

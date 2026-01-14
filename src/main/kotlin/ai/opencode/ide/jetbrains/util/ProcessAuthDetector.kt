package ai.opencode.ide.jetbrains.util

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility to detect OpenCode server authentication credentials from running processes.
 */
object ProcessAuthDetector {
    private val logger = Logger.getInstance(ProcessAuthDetector::class.java)
    
    data class ServerAuth(
        val username: String = "opencode",
        val password: String? = null
    )
    
    /**
     * Detects authentication credentials for OpenCode server running on the specified port.
     * Scans running processes to find opencode-cli serve and extracts OPENCODE_SERVER_PASSWORD.
     */
    fun detectAuthForPort(port: Int): ServerAuth {
        try {
            val osName = System.getProperty("os.name").lowercase()
            
            return when {
                osName.contains("win") -> detectAuthForPortWindows(port)
                osName.contains("mac") || osName.contains("darwin") -> detectAuthForPortUnix(port, "ps eww")
                osName.contains("linux") -> detectAuthForPortUnix(port, "ps auxeww")
                else -> {
                    logger.debug("Process auth detection not supported on OS: $osName")
                    ServerAuth()
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to detect auth from process: ${e.message}")
            return ServerAuth()
        }
    }
    
    private fun detectAuthForPortUnix(port: Int, psCommand: String): ServerAuth {
        try {
            // Step 1: Find PID using lsof
            val lsofCommand = arrayOf("sh", "-c", "lsof -ti :$port -sTCP:LISTEN")
            val lsofProcess = Runtime.getRuntime().exec(lsofCommand)
            val lsofReader = BufferedReader(InputStreamReader(lsofProcess.inputStream))
            
            var pid: String? = null
            lsofReader.use {
                pid = it.readLine()?.trim()
            }
            lsofProcess.waitFor()
            
            if (pid == null) {
                logger.debug("No process found listening on port $port (Unix)")
                return ServerAuth()
            }
            
            // Step 2: Get process environment using ps with specific PID
            val psEnvCommand = arrayOf("sh", "-c", "$psCommand $pid")
            val psProcess = Runtime.getRuntime().exec(psEnvCommand)
            val psReader = BufferedReader(InputStreamReader(psProcess.inputStream))
            
            psReader.use {
                var line: String?
                while (psReader.readLine().also { line = it } != null) {
                    val processLine = line ?: continue
                    
                    if (processLine.contains("opencode-cli") && processLine.contains("serve")) {
                        val password = extractEnvVar(processLine, "OPENCODE_SERVER_PASSWORD")
                        val username = extractEnvVar(processLine, "OPENCODE_SERVER_USERNAME") ?: "opencode"
                        
                        if (password != null) {
                            // Note: Password is not logged for security reasons
                            logger.info("Detected authentication for port $port: username=$username")
                            return ServerAuth(username, password)
                        } else {
                            // Found opencode-cli process but no password - use no-auth
                            logger.debug("Found opencode-cli on port $port but no password detected, using no-auth")
                            return ServerAuth()
                        }
                    }
                }
            }
            psProcess.waitFor()
        } catch (e: Exception) {
            logger.debug("Unix process detection failed: ${e.message}")
        }
        
        // Fallback: return no auth (will attempt connection without credentials)
        logger.debug("Unix auth detection failed, falling back to no-auth connection")
        return ServerAuth()
    }
    
    private fun detectAuthForPortWindows(port: Int): ServerAuth {
        try {
            // Windows: Use netstat to find PID, then try PowerShell to get environment variables
            val netstatCommand = arrayOf("cmd", "/c", "netstat -ano | findstr :$port | findstr LISTENING")
            val netstatProcess = Runtime.getRuntime().exec(netstatCommand)
            val netstatReader = BufferedReader(InputStreamReader(netstatProcess.inputStream))
            
            var pid: String? = null
            netstatReader.use {
                val line = it.readLine()
                if (line != null) {
                    // Extract PID from netstat output (last column)
                    pid = line.trim().split("\\s+".toRegex()).lastOrNull()
                }
            }
            netstatProcess.waitFor()
            
            if (pid == null) {
                logger.debug("No process found listening on port $port (Windows)")
                return ServerAuth()
            }
            
            // Verify it's opencode-cli process using tasklist
            val tasklistCommand = arrayOf("cmd", "/c", "tasklist /FI \"PID eq $pid\" /FO CSV /NH")
            val tasklistProcess = Runtime.getRuntime().exec(tasklistCommand)
            val tasklistReader = BufferedReader(InputStreamReader(tasklistProcess.inputStream))
            
            var isOpenCodeProcess = false
            tasklistReader.use {
                val line = it.readLine()
                if (line != null && line.contains("opencode", ignoreCase = true)) {
                    isOpenCodeProcess = true
                }
            }
            tasklistProcess.waitFor()
            
            if (!isOpenCodeProcess) {
                logger.debug("Process $pid is not opencode-cli (Windows)")
                return ServerAuth()
            }
            
            // Try to get environment variables via PowerShell
            return detectAuthForPortWindowsPowerShell(pid)
        } catch (e: Exception) {
            logger.debug("Windows process detection failed: ${e.message}")
        }
        
        // Fallback: return no auth (will attempt connection without credentials)
        logger.debug("Windows auth detection failed, falling back to no-auth connection")
        return ServerAuth()
    }
    
    private fun detectAuthForPortWindowsPowerShell(pid: String): ServerAuth {
        try {
            // PowerShell command to get environment variables of a process
            // Note: This may not work for already-running processes due to Windows security restrictions
            val psCommand = """
                (Get-Process -Id $pid).StartInfo.EnvironmentVariables | 
                Where-Object { ${'$'}_.Key -like 'OPENCODE_SERVER_*' } | 
                ForEach-Object { "${'$'}(${'$'}_.Key)=${'$'}(${'$'}_.Value)" }
            """.trimIndent()
            
            val command = arrayOf("powershell", "-Command", psCommand)
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var password: String? = null
            var username: String? = null
            
            reader.use {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val envLine = line ?: continue
                    if (envLine.startsWith("OPENCODE_SERVER_PASSWORD=")) {
                        password = envLine.substringAfter("=")
                    } else if (envLine.startsWith("OPENCODE_SERVER_USERNAME=")) {
                        username = envLine.substringAfter("=")
                    }
                }
            }
            process.waitFor()
            
            if (password != null) {
                // Note: Password is not logged for security reasons
                logger.info("Detected authentication via PowerShell: username=${username ?: "opencode"}")
                return ServerAuth(username ?: "opencode", password)
            }
            
            // PowerShell method failed, fallback to no-auth
            logger.debug("PowerShell could not retrieve environment variables, falling back to no-auth")
        } catch (e: Exception) {
            logger.debug("PowerShell process detection failed: ${e.message}")
        }
        
        // Fallback: return no auth (will attempt connection without credentials)
        return ServerAuth()
    }
    
    private fun extractEnvVar(processLine: String, varName: String): String? {
        val pattern = Regex("$varName=([^\\s]+)")
        val match = pattern.find(processLine)
        return match?.groupValues?.getOrNull(1)
    }
}

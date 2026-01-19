package ai.opencode.ide.jetbrains.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.Charset

/**
 * Utility to detect OpenCode server authentication credentials from running processes.
 * Uses IntelliJ Platform's GeneralCommandLine for robust process execution across OS.
 */
object ProcessAuthDetector {
    private val logger = Logger.getInstance(ProcessAuthDetector::class.java)
    
    data class ServerAuth(
        val username: String = "opencode",
        val password: String? = null
    )
    
    /**
     * Detects authentication credentials for OpenCode server running on the specified port.
     * Scans running processes to find OpenCode server processes and extracts OPENCODE_SERVER_PASSWORD.
     */
    fun detectAuthForPort(port: Int): ServerAuth {
        try {
            val osName = System.getProperty("os.name").lowercase()
            
            return when {
                osName.contains("win") -> detectAuthForPortWindows(port)
                osName.contains("mac") || osName.contains("darwin") -> detectAuthForPortUnix(port, listOf("ps", "eww"))
                osName.contains("linux") -> detectAuthForPortUnix(port, listOf("ps", "auxeww"))
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
    
    private fun detectAuthForPortUnix(port: Int, psCommandParts: List<String>): ServerAuth {
        try {
            // Step 1: Find PID using lsof
            // lsof -ti :$port -sTCP:LISTEN
            val lsofOutput = runCommand(listOf("lsof", "-ti", ":$port", "-sTCP:LISTEN")) ?: return ServerAuth()
            
            val pid = lsofOutput.stdout.trim().lines().firstOrNull { it.isNotBlank() }?.trim()
            
            if (pid == null) {
                logger.debug("No process found listening on port $port (Unix)")
                return ServerAuth()
            }
            
            // Step 2: Get process environment using ps with specific PID
            // ps eww $pid
            val psArgs = psCommandParts + pid
            val psOutput = runCommand(psArgs) ?: return ServerAuth()
            
            for (line in psOutput.stdoutLines) {
                val isOpenCodeProcess = line.contains("opencode")
                // We already confirmed this PID is listening on the port via lsof/netstat.
                // So if it's named "opencode", it's our target. No need to check for "serve" or "web" args,
                // as the user might run it in TUI mode or other future modes.
                if (isOpenCodeProcess) {
                    val password = extractEnvVar(line, "OPENCODE_SERVER_PASSWORD")
                    val username = extractEnvVar(line, "OPENCODE_SERVER_USERNAME") ?: "opencode"
                    
                    if (password != null) {
                        // Note: Password is not logged for security reasons
                        logger.info("Detected authentication for port $port: username=$username")
                        return ServerAuth(username, password)
                    } else {
                        // Found OpenCode process but no password - use no-auth
                        logger.debug("Found OpenCode server on port $port but no password detected, using no-auth")
                        return ServerAuth()
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Unix process detection failed: ${e.message}")
        }
        
        // Fallback: return no auth
        logger.debug("Unix auth detection failed, falling back to no-auth connection")
        return ServerAuth()
    }
    
    private fun detectAuthForPortWindows(port: Int): ServerAuth {
        try {
            // Windows: Use netstat to find PID
            // netstat -ano | findstr :$port | findstr LISTENING
            // Note: We need cmd /c to handle pipes
            val netstatOutput = runCommand(listOf("cmd", "/c", "netstat -ano | findstr :$port | findstr LISTENING")) ?: return ServerAuth()
            
            // Extract PID from netstat output (last column)
            // TCP    0.0.0.0:4096           0.0.0.0:0              LISTENING       1234
            val pid = netstatOutput.stdout.trim().lines().firstOrNull { it.isNotBlank() }
                ?.trim()?.split("\\s+".toRegex())?.lastOrNull()
            
            if (pid == null) {
                logger.debug("No process found listening on port $port (Windows)")
                return ServerAuth()
            }
            
            // Verify it's OpenCode process using tasklist
            // tasklist /FI "PID eq $pid" /FO CSV /NH
            val tasklistOutput = runCommand(listOf("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")) ?: return ServerAuth()
            
            // "opencode.exe","1234","Console","1","10,000 K"
            val isOpenCodeProcess = tasklistOutput.stdoutLines.any { 
                it.contains("opencode", ignoreCase = true) 
            }
            
            if (!isOpenCodeProcess) {
                logger.debug("Process $pid is not OpenCode (Windows)")
                return ServerAuth()
            }
            
            // Try to get environment variables via PowerShell
            return detectAuthForPortWindowsPowerShell(pid)
        } catch (e: Exception) {
            logger.debug("Windows process detection failed: ${e.message}")
        }
        
        // Fallback: return no auth
        logger.debug("Windows auth detection failed, falling back to no-auth connection")
        return ServerAuth()
    }
    
    private fun detectAuthForPortWindowsPowerShell(pid: String): ServerAuth {
        try {
            // PowerShell command to get environment variables of a process
            val psCommand = """
                (Get-Process -Id $pid).StartInfo.EnvironmentVariables | 
                Where-Object { ${'$'}_.Key -like 'OPENCODE_SERVER_*' } | 
                ForEach-Object { "${'$'}(${'$'}_.Key)=${'$'}(${'$'}_.Value)" }
            """.trimIndent()
            
            val output = runCommand(listOf("powershell", "-Command", psCommand)) ?: return ServerAuth()
            
            var password: String? = null
            var username: String? = null
            
            for (line in output.stdoutLines) {
                if (line.startsWith("OPENCODE_SERVER_PASSWORD=")) {
                    password = line.substringAfter("=")
                } else if (line.startsWith("OPENCODE_SERVER_USERNAME=")) {
                    username = line.substringAfter("=")
                }
            }
            
            if (password != null) {
                logger.info("Detected authentication via PowerShell: username=${username ?: "opencode"}")
                return ServerAuth(username ?: "opencode", password)
            }
            
            logger.debug("PowerShell could not retrieve environment variables, falling back to no-auth")
        } catch (e: Exception) {
            logger.debug("PowerShell process detection failed: ${e.message}")
        }
        
        return ServerAuth()
    }
    
    private fun extractEnvVar(processLine: String, varName: String): String? {
        val pattern = Regex("$varName=([^\\s]+)")
        val match = pattern.find(processLine)
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Executes a command using IntelliJ's GeneralCommandLine and CapturingProcessHandler.
     * Provides better OS compatibility, escaping, and timeout handling than Runtime.exec().
     */
    private fun runCommand(command: List<String>, timeoutMs: Int = 3000): ProcessOutput? {
        return try {
            val cmd = GeneralCommandLine(command)
                .withCharset(Charset.defaultCharset())
            
            val handler = CapturingProcessHandler(cmd)
            val output = handler.runProcess(timeoutMs)
            
            if (output.isTimeout) {
                logger.debug("Command timed out: $command")
                return null
            }
            output
        } catch (e: Exception) {
            logger.debug("Command execution failed: $command", e)
            null
        }
    }
}

package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.util.PortFinder
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RealProcessIntegrationTest : BasePlatformTestCase() {

    private var process: Process? = null
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        // Find an available port first
        port = PortFinder.findAvailablePort()
        
        // Find opencode executable
        val path = System.getenv("PATH").split(File.pathSeparator)
            .map { File(it, "opencode") }
            .firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath ?: throw IllegalStateException("opencode binary not found in PATH")

        println("Launching opencode: $path serve --port $port")
        
        val pb = ProcessBuilder(path, "serve", "--port", "$port")
        pb.redirectErrorStream(true)
        process = pb.start()
        
        // Start a thread to read stdout to prevent blocking and verify startup
        Thread {
            try {
                process?.inputStream?.bufferedReader()?.use { reader ->
                    reader.forEachLine { println("[RealServer] $it") }
                }
            } catch (e: Exception) {
                // Ignore stream closed
            }
        }.start()
        
        // Wait for port to be open and HEALTHY
        val ready = PortFinder.waitForPort(port, "127.0.0.1", 15000, true)
        if (!ready) {
            throw IllegalStateException("opencode server failed to start on port $port")
        }
    }

    override fun tearDown() {
        try {
            process?.destroy()
            process?.waitFor(2, TimeUnit.SECONDS)
            if (process?.isAlive == true) {
                process?.destroyForcibly()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testRealConnectionAndApi() {
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val dvs = project.service<DiffViewerService>()
        
        val client = OpenCodeApiClient("127.0.0.1", port)
        
        // Inject dependencies
        service.setTestDeps(sm, dvs, client)
        
        // Connect
        println("Connecting plugin to real server on port $port...")
        val connectMethod = OpenCodeService::class.java.getDeclaredMethod("connectToSse")
        connectMethod.isAccessible = true
        connectMethod.invoke(service)
        
        // Verify SSE connection establishment (wait up to 5s)
        val connectedField = OpenCodeService::class.java.getDeclaredField("isConnected")
        connectedField.isAccessible = true
        
        var connected = false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000) {
            val isConn = (connectedField.get(service) as AtomicBoolean).get()
            if (isConn) {
                connected = true
                break
            }
            Thread.sleep(100)
        }
        
        assertTrue("Failed to establish SSE connection to real server", connected)
        println("SSE Connection established.")
        
        // Verify API Call
        try {
            // Try to get a dummy session (Must start with "ses" to pass validation)
            val session = client.getSession("ses-test-session", project.basePath ?: ".")
            println("API getSession returned: $session")
            // Reaching here implies network success
        } catch (e: Exception) {
            e.printStackTrace()
            fail("API call failed: ${e.message}")
        }
    }

    fun testAcceptAndReject() {
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        // We don't really need a real client for this test as we invoke SM methods directly, 
        // but we need to inject dependencies to satisfy the service structure if needed.
        val client = OpenCodeApiClient("127.0.0.1", port)
        
        // Mock DiffViewerService to capture entries without trying to open real UI windows
        val mockDiffViewer = object : DiffViewerService(project) {
            override fun showMultiFileDiff(entries: List<ai.opencode.ide.jetbrains.api.models.DiffEntry>, initialIndex: Int?) {
                println("MockViewer showing ${entries.size} diffs")
            }
        }
        
        service.setTestDeps(sm, mockDiffViewer, client)
        
        // Setup Git Repo in temp project
        val projectDir = File(project.basePath!!)
        if (!projectDir.exists()) projectDir.mkdirs()
        
        runCommand(projectDir, "git", "init")
        // Config git user to allow committing
        runCommand(projectDir, "git", "config", "user.name", "TestUser")
        runCommand(projectDir, "git", "config", "user.email", "test@example.com")
        
        // 1. Create a file and commit it (Baseline state)
        // Use Real File System because SessionManager uses LocalFileSystem
        val relPath = "test.txt"
        val ioFile = File(projectDir, relPath)
        ioFile.writeText("Initial Content")
        
        // Refresh VFS to pick it up
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        assertNotNull(vf)
        
        val filePath = ioFile.absolutePath
        
        runCommand(projectDir, "git", "add", ".")
        runCommand(projectDir, "git", "commit", "-m", "Initial commit")
        
        // 2. Start Turn (Captures LocalHistory label)
        sm.onTurnStart()
        sm.onFileEdited(relPath)
        
        // Simulate AI modifying the file content (On Disk + Refresh)
        com.intellij.openapi.application.runWriteAction {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf!!, "AI Modified Content")
        }
        
        // 3. Create Snapshot and Process Diffs
        val snapshot = sm.onTurnEnd() ?: throw AssertionError("onTurnEnd returned null")
        val diffs = listOf(
            ai.opencode.ide.jetbrains.api.models.FileDiff(
                file = relPath,
                before = "Initial Content",
                after = "AI Modified Content",
                additions = 1,
                deletions = 1
            )
        )
        val entries = sm.processDiffs(diffs, snapshot)
        assertTrue(entries.isNotEmpty())
        
        // In test environment, provide fallback explicitly
        val resolvedBefore = sm.resolveBeforeContent(relPath, "Initial Content", snapshot)
        val entry = entries.first().copy(resolvedBefore = resolvedBefore)
        
        // 4. Test REJECT
        // Expectation: File reverts to "Initial Content"
        val rejectLatch = java.util.concurrent.CountDownLatch(1)
        sm.rejectDiff(entry) { success ->
            assertTrue("Reject should succeed", success)
            rejectLatch.countDown()
        }
        waitForLatch(rejectLatch)
        
        vf!!.refresh(false, false)
        val vfsContent = String(vf.contentsToByteArray())
        println("VFS Content after reject: $vfsContent")
        
        assertEquals("Initial Content", vfsContent)
        println("Reject verified: File reverted successfully.")
        
        // 5. Test ACCEPT
        // Reset to modified state for Accept test
        com.intellij.openapi.application.runWriteAction {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, "Final Accepted Content")
        }
        
        val acceptLatch = java.util.concurrent.CountDownLatch(1)
        sm.acceptDiff(entry) { success ->
            assertTrue("Accept should succeed", success)
            acceptLatch.countDown()
        }
        waitForLatch(acceptLatch)
        
        // Verify git status -> Should be staged "M  test.txt" (M space)
        val status = runCommand(projectDir, "git", "status", "--porcelain")
        println("Git Status after accept: [$status]")
        assertTrue("File should be staged", status.trim().startsWith("M") || status.trim().startsWith("A"))
        println("Accept verified: File staged in git.")
    }

    fun testEmptyFileSafety() {
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val client = OpenCodeApiClient("127.0.0.1", port)
        
        val mockDiffViewer = object : DiffViewerService(project) {
            override fun showMultiFileDiff(entries: List<ai.opencode.ide.jetbrains.api.models.DiffEntry>, initialIndex: Int?) {}
        }
        
        service.setTestDeps(sm, mockDiffViewer, client)
        
        val projectDir = File(project.basePath!!)
        if (!projectDir.exists()) projectDir.mkdirs()
        
        runCommand(projectDir, "git", "init")
        runCommand(projectDir, "git", "config", "user.name", "TestUser")
        runCommand(projectDir, "git", "config", "user.email", "test@example.com")
        
        // 1. Create EMPTY file and commit
        val relPath = "empty.txt"
        val ioFile = File(projectDir, relPath)
        ioFile.writeText("")
        
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)!!
        runCommand(projectDir, "git", "add", ".")
        runCommand(projectDir, "git", "commit", "-m", "Add empty file")
        
        // 2. AI Modifies it
        sm.onTurnStart()
        // Simulate VFS change (should be picked up by listener too, but we help it manually just in case listener is async/slow)
        // Actually SessionManager listener is synchronous on VFS events.
        
        com.intellij.openapi.application.runWriteAction {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, "AI Content")
        }
        
        // 3. Reject
        val snapshot = sm.onTurnEnd()!!
        
        // Verify that 'isCreatedExplicitly' is FALSE
        // 'aiCreatedFiles' should NOT contain it because we only did 'saveText' (modification).
        assertFalse("Should not be marked as created", snapshot.aiCreatedFiles.contains(relPath))
        
        val diffs = listOf(
            ai.opencode.ide.jetbrains.api.models.FileDiff(
                file = relPath,
                before = "", // Empty original content
                after = "AI Content",
                additions = 1,
                deletions = 0
            )
        )
        val entries = sm.processDiffs(diffs, snapshot)
        assertNotEmpty(entries)
        val entry = entries.first()
        
        // Verify isNewFile logic (Strict check)
        assertFalse("Should NOT be considered new file", entry.isNewFile)
        
        // Provide before content (empty) explicitly
        val resolvedEntry = entry.copy(resolvedBefore = "")
        
        val latch = java.util.concurrent.CountDownLatch(1)
        sm.rejectDiff(resolvedEntry) { 
            latch.countDown()
        }
        waitForLatch(latch)
        
        vf.refresh(false, false)
        assertTrue("File should still exist", vf.exists())
        assertEquals("File should be empty", "", String(vf.contentsToByteArray()))
        println("Empty File Safety Verified: File restored to empty, not deleted.")
    }
    
    private fun waitForLatch(latch: java.util.concurrent.CountDownLatch) {
        val start = System.currentTimeMillis()
        while (latch.count > 0) {
            if (System.currentTimeMillis() - start > 10000) {
                throw AssertionError("Timeout waiting for latch")
            }
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
    
    private fun runCommand(dir: File, vararg command: String): String {
        val cmd = com.intellij.execution.configurations.GeneralCommandLine(*command)
            .withWorkDirectory(dir)
        val handler = com.intellij.execution.process.CapturingProcessHandler(cmd)
        val output = handler.runProcess(10000)
        if (output.exitCode != 0) {
             throw RuntimeException("Command failed: ${command.joinToString(" ")}\nStderr: ${output.stderr}\nStdout: ${output.stdout}")
        }
        return output.stdout
    }
}

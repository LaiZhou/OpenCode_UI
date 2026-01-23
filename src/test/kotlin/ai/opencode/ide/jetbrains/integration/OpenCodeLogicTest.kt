package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Logic Unit Test using Fake Server.
 * Covers complex Diff scenarios (Turn Isolation, Race Conditions).
 */
class OpenCodeLogicTest {
    
    private var server: FakeOpenCodeServer? = null
    private var runner: TestRunner? = null
    
    @Before
    fun setup() {
        val port = 14000 + (Math.random() * 1000).toInt()
        server = FakeOpenCodeServer(port)
        server!!.start()
        
        runner = TestRunner(port)
    }
    
    @After
    fun tearDown() {
        server?.stop()
        server = null
    }
    
    @Test
    fun testDiffScenarios() {
        println("=== Starting OpenCode Logic Tests (Diff Isolation) ===")
        val r = runner!!
        val s = server!!
        
        try {
            // Wait for connection to establish
            Thread.sleep(500)
            
            println("\n--------------------------------------------------")
            println("TEST: Scenario A: Normal Turn")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // 1. Start Turn
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""")
            
            // 2. Edit file
            s.broadcast("""{"type":"file.edited","properties":{"file":"a.kt"}}""")
            
            // 3. Update Message
            s.broadcast("""{"type":"message.updated","properties":{"info":{"id":"msg-1","sessionID":"s1","role":"assistant"}}}""")
            
            // 4. End Turn
            s.setDiffResponse("msg-1", """[{"file":"a.kt","before":"old","after":"new","additions":1,"deletions":0}]""")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}""")
            
            // Verify
            r.waitForDiffs(1)
            r.assertDiffShown("a.kt")
            println("✓ Passed")

            println("\n--------------------------------------------------")
            println("TEST: Scenario B: Pure Conversation (No Edits)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""")
            s.broadcast("""{"type":"message.updated","properties":{"info":{"id":"msg-2","sessionID":"s1","role":"assistant"}}}""")
            
            s.setDiffResponse("msg-2", "[]")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}""")
            
            r.waitForDiffs(0, timeoutMs = 1000)
            r.assertNoDiffsShown()
            println("✓ Passed")

            println("\n--------------------------------------------------")
            println("TEST: Scenario C: Turn Isolation (The Fix)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // Turn 1
            println("  Step 1: Turn 1 edits a.kt")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""")
            s.broadcast("""{"type":"file.edited","properties":{"file":"a.kt"}}""")
            s.broadcast("""{"type":"message.updated","properties":{"info":{"id":"msg-3","sessionID":"s1","role":"assistant"}}}""")
            
            s.setDiffResponse("msg-3", """[{"file":"a.kt","before":"old","after":"new","additions":1,"deletions":0}]""")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}""")
            
            r.waitForDiffs(1)
            r.assertDiffShown("a.kt")
            
            // Turn 2: Starts immediately
            println("  Step 2: Turn 2 starts (clearing real-time state)")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""")
            
            // Turn 2: Pure chat
            s.broadcast("""{"type":"message.updated","properties":{"info":{"id":"msg-4","sessionID":"s1","role":"assistant"}}}""")
            s.setDiffResponse("msg-4", "[]")
            
            // Turn 2 Ends
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}""")
            
            r.waitForDiffs(0, timeoutMs = 1000)
            r.assertNoDiffsShown()
            println("✓ Passed")
            
            println("\n--------------------------------------------------")
            println("TEST: Scenario D: Fast Rapid Turns (Race Condition)")
            println("--------------------------------------------------")
            
            r.resetState()
            
            // Turn 1
            println("  Step 1: Turn 1 edits b.kt")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""")
            s.broadcast("""{"type":"file.edited","properties":{"file":"b.kt"}}""")
            s.broadcast("""{"type":"message.updated","properties":{"info":{"id":"msg-5","sessionID":"s1","role":"assistant"}}}""")
            
            // Delay the API response to simulate network latency
            s.setDiffResponse("msg-5", """[{"file":"b.kt","before":"old","after":"new","additions":1,"deletions":0}]""", delayMs = 200)
            
            // Turn 1 Ends
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}""")
            
            // IMMEDIATELY Start Turn 2 (before Turn 1 fetch completes)
            println("  Step 2: Turn 2 starts immediately")
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""")
            s.broadcast("""{"type":"file.edited","properties":{"file":"c.kt"}}""")
            
            // Verify Turn 1 diffs still show up correctly despite Turn 2 being busy
            r.waitForDiffs(1, timeoutMs = 3000) // Wait longer for delayed response
            r.assertDiffShown("b.kt")
            
            // Finish Turn 2
            s.broadcast("""{"type":"message.updated","properties":{"info":{"id":"msg-6","sessionID":"s1","role":"assistant"}}}""")
            s.setDiffResponse("msg-6", """[{"file":"c.kt","before":"old","after":"new","additions":1,"deletions":0}]""")
            
            s.broadcast("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}""")
            
            r.waitForDiffs(1)
            r.assertDiffShown("c.kt")
            println("✓ Passed")

            println("\nAll tests passed!")
            
        } catch (e: Throwable) {
            println("\n!!! TEST FAILED: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}

class TestRunner(val serverPort: Int) {
    val mockProject: Project
    val mockDiffViewer: MockDiffViewerService
    val sessionManager: TestSessionManager
    val openCodeService: OpenCodeService

    init {
        // Setup Mocks
        mockProject = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> "/tmp/test"
                "isDisposed" -> false
                "toString" -> "MockProject"
                "hashCode" -> 12345
                "equals" -> false
                else -> null
            }
        } as Project

        mockDiffViewer = MockDiffViewerService(mockProject)
        sessionManager = TestSessionManager(mockProject)
        
        // Initialize Service with REAL ApiClient pointing to Fake Server
        val apiClient = OpenCodeApiClient("127.0.0.1", serverPort)
        
        openCodeService = OpenCodeService(mockProject)
        openCodeService.setTestDeps(sessionManager, mockDiffViewer, apiClient)
        
        // Mock UI execution
        openCodeService.invokeLater = { r -> r.run() }
        
        // Trigger connection manually
        val connectMethod = OpenCodeService::class.java.getDeclaredMethod("connectToSse")
        connectMethod.isAccessible = true
        connectMethod.invoke(openCodeService)
    }
    
    fun resetState() {
        sessionManager.clear()
        mockDiffViewer.shownDiffs.clear()
    }
    
    fun waitForDiffs(count: Int, timeoutMs: Long = 2000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (mockDiffViewer.shownDiffs.size >= count && count > 0) return
            if (count == 0 && mockDiffViewer.shownDiffs.isNotEmpty()) throw AssertionError("Expected 0 diffs, got ${mockDiffViewer.shownDiffs.size}")
            Thread.sleep(50)
        }
        if (mockDiffViewer.shownDiffs.size != count) {
            if (count == 0) return 
            throw AssertionError("Timeout waiting for diffs. Expected $count, got ${mockDiffViewer.shownDiffs.size}")
        }
    }
    
    fun assertDiffShown(file: String) {
        val shown = mockDiffViewer.shownDiffs.lastOrNull() ?: throw AssertionError("No diffs shown")
        val files = shown.map { it.file }
        if (file !in files) throw AssertionError("File $file not in shown diffs: $files")
        mockDiffViewer.shownDiffs.clear()
    }
    
    fun assertNoDiffsShown() {
        if (mockDiffViewer.shownDiffs.isNotEmpty()) {
            throw AssertionError("Expected no diffs, but got: ${mockDiffViewer.shownDiffs.map { it.map { e -> e.file } }}")
        }
    }
}

class MockDiffViewerService(project: Project) : DiffViewerService(project) {
    val shownDiffs = CopyOnWriteArrayList<List<DiffEntry>>()
    
    override fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int?) {
        println("  [MockViewer] Showing diffs: ${entries.map { it.file }}")
        shownDiffs.add(entries)
    }
}

class TestSessionManager(project: Project) : SessionManager(project) {
    override fun createSystemLabel(name: String): Label? {
        return null
    }
}

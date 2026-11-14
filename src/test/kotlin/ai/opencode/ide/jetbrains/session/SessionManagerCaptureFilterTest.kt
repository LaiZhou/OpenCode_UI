package ai.opencode.ide.jetbrains.session

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile

/**
 * Guards the memory limits added in 1.1.3: oversized files must be skipped but still recorded as
 * changed, and the per-turn budget must stop further content capture.
 */
class SessionManagerCaptureFilterTest : LightPlatformTestCase() {

    private lateinit var manager: SessionManager

    override fun setUp() {
        super.setUp()
        manager = SessionManager(project)
    }

    override fun tearDown() {
        try {
            manager.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun testLargeFileIsSkippedButPathIsStillRecorded() {
        val large = LightVirtualFile("libtauri-14ea5ab6d8e739f1.rlib", "x".repeat(2 * 1024 * 1024))

        manager.captureContentBeforeChange(large)

        assertEmpty(capturedKeys())
        assertFalse(manager.getLiveVfsChangedFiles().isEmpty())
    }

    fun testSmallTextFileIsStillCaptured() {
        val small = LightVirtualFile("notes.txt", "hello")

        manager.captureContentBeforeChange(small)

        assertEquals(1, capturedKeys().size)
    }

    fun testTurnBudgetStopsFurtherCaptures() {
        val bytesField = SessionManager::class.java.getDeclaredField("capturedBeforeBytes")
        bytesField.isAccessible = true
        bytesField.setLong(manager, 64L * 1024 * 1024)

        manager.captureContentBeforeChange(LightVirtualFile("small.txt", "hello"))

        assertEmpty(capturedKeys())
        assertFalse(manager.getLiveVfsChangedFiles().isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun capturedKeys(): Set<String> {
        val field = SessionManager::class.java.getDeclaredField("capturedBeforeContent")
        field.isAccessible = true
        return (field.get(manager) as Map<String, String>).keys
    }
}

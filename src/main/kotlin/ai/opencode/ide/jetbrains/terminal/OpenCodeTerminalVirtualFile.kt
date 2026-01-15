package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightVirtualFile
import java.util.UUID
import javax.swing.Icon

/**
 * Virtual file representing an OpenCode terminal tab in the editor area.
 */
class OpenCodeTerminalVirtualFile(
    private val terminalName: String = "OpenCode"
) : LightVirtualFile(terminalName) {

    // Use a unique ID to ensure the file path is unique for each instance.
    // This prevents FileEditorManager from confusing this file with stale/cached instances
    // from previous sessions, which can cause deadlocks or "No file exists" errors.
    private val uniqueId = UUID.randomUUID().toString()

    override fun getFileType(): FileType = OpenCodeTerminalFileType

    override fun isWritable(): Boolean = false

    override fun getPath(): String = "opencode-terminal://$terminalName/$uniqueId"

    override fun toString(): String = "OpenCodeTerminalVirtualFile($terminalName-$uniqueId)"

    object OpenCodeTerminalFileType : FakeFileType() {
        override fun getName(): String = "OpenCode Terminal"
        override fun getDescription(): String = "OpenCode Terminal"
        override fun isMyFileType(file: VirtualFile): Boolean = file is OpenCodeTerminalVirtualFile
        override fun getIcon(): Icon? = null
    }
}

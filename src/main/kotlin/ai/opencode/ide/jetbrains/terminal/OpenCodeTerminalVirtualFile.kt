package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightVirtualFile
import javax.swing.Icon

/**
 * Virtual file representing an OpenCode terminal tab in the editor area.
 */
class OpenCodeTerminalVirtualFile(
    private val terminalName: String = "OpenCode"
) : LightVirtualFile(terminalName) {

    override fun getFileType(): FileType = OpenCodeTerminalFileType

    override fun isWritable(): Boolean = false

    override fun getPath(): String = "opencode-terminal://$terminalName"

    override fun toString(): String = "OpenCodeTerminalVirtualFile($terminalName)"

    object OpenCodeTerminalFileType : FakeFileType() {
        override fun getName(): String = "OpenCode Terminal"
        override fun getDescription(): String = "OpenCode Terminal"
        override fun isMyFileType(file: VirtualFile): Boolean = file is OpenCodeTerminalVirtualFile
        override fun getIcon(): Icon? = null
    }
}

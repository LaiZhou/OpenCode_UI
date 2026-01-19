package ai.opencode.ide.jetbrains.web

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.util.UUID
import javax.swing.Icon

import com.intellij.openapi.util.Key

class OpenCodeWebVirtualFile(
    private val tabName: String,
    val targetUrl: String
) : LightVirtualFile(tabName) {

    companion object {
        val PASSWORD_KEY = Key.create<String>("OPENCODE_PASSWORD")
    }

    private val uniqueId = UUID.randomUUID().toString()

    override fun getFileType(): FileType = OpenCodeWebFileType

    override fun isWritable(): Boolean = false

    override fun getPath(): String = "opencode-web://$tabName/$uniqueId"

    override fun toString(): String = "OpenCodeWebVirtualFile($tabName-$uniqueId)"

    object OpenCodeWebFileType : FakeFileType() {
        override fun getName(): String = "OpenCode Web"
        override fun getDescription(): String = "OpenCode Web Interface"
        override fun isMyFileType(file: VirtualFile): Boolean = file is OpenCodeWebVirtualFile
        override fun getIcon(): Icon? = null
    }
}

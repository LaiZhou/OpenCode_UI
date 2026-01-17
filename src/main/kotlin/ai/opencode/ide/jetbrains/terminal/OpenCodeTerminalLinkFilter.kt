package ai.opencode.ide.jetbrains.terminal

import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import org.jetbrains.plugins.terminal.ShellTerminalWidget

import java.util.regex.Pattern

/**
 * Jediterm hyperlink filter that makes @path, @"path with spaces", and @path#Lx-y clickable in the terminal.
 * Clicking opens the file and highlights the specified line range.
 */
class OpenCodeTerminalLinkFilter private constructor(
    private val project: Project,
    private val widget: ShellTerminalWidget
) : HyperlinkFilter {

    override fun apply(line: String): LinkResult? {
        val matcher = LINK_PATTERN.matcher(line)
        val items = mutableListOf<LinkResultItem>()

        while (matcher.find()) {
            val rawFilePath = matcher.group(2) ?: matcher.group(3) ?: continue
            
            // Remove Jediterm wide character placeholders (U+E000)
            // Jediterm uses U+E000 as a placeholder for the second cell of wide characters (CJK)
            val filePath = rawFilePath.filter { it.code != 0xE000 }
            
            logger.debug("Cleaned filePath: '$filePath' (raw: '$rawFilePath')")
            
            val startLine = matcher.group(5)?.toIntOrNull()
            val endLine = matcher.group(7)?.toIntOrNull() ?: startLine

            val linkInfo = LinkInfo {
                navigateToFile(project, filePath, startLine, endLine)
            }

            items.add(LinkResultItem(matcher.start(), matcher.end(), linkInfo))
        }

        return if (items.isEmpty()) null else LinkResult(items)
    }

    private fun navigateToFile(project: Project, filePath: String, startLine: Int?, endLine: Int?) {
        val virtualFile = resolveFile(project, filePath)
        if (virtualFile == null) {
            logger.warn("File not found: $filePath")
            return
        }

        val descriptor = if (startLine != null) {
            val targetLine = startLine.coerceAtLeast(1)
            OpenFileDescriptor(project, virtualFile, targetLine - 1, 0)
        } else {
            OpenFileDescriptor(project, virtualFile)
        }

        // Open editor immediately
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            if (editor != null && startLine != null) {
                val document = editor.document
                val startIndex = (startLine - 1).coerceIn(0, document.lineCount - 1)
                val endIndex = (endLine ?: startLine).minus(1).coerceIn(startIndex, document.lineCount - 1)
                val startOffset = document.getLineStartOffset(startIndex)
                val endOffset = document.getLineEndOffset(endIndex)

                editor.selectionModel.setSelection(startOffset, endOffset)
                editor.caretModel.moveToOffset(startOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
    }

    private fun resolveFile(project: Project, filePath: String): VirtualFile? {
        val resolvedPath = PathUtil.resolveProjectPath(project, filePath) ?: return null
        return LocalFileSystem.getInstance().findFileByPath(resolvedPath)
    }

    companion object {
        private val logger = Logger.getInstance(OpenCodeTerminalLinkFilter::class.java)
        private val LINK_PATTERN = Pattern.compile("@(\"([^\"]+)\"|([^\\s#]+))(#L(\\d+)(-(\\d+))?)?")

        fun install(project: Project, widget: ShellTerminalWidget) {
            widget.addHyperlinkFilter(OpenCodeTerminalLinkFilter(project, widget))
            logger.info("OpenCode terminal link filter installed")
        }
    }
}

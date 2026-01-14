package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Paths

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import java.lang.ref.WeakReference
import com.intellij.openapi.application.ApplicationManager

/**
 * Service for showing diffs using JetBrains native DiffManager.
 */
@Service(Service.Level.PROJECT)
class DiffViewerService(private val project: Project) {

    private val logger = Logger.getInstance(DiffViewerService::class.java)
    
    // Track the last opened diff file to close it before opening a new one
    private var lastOpenedDiffFile: WeakReference<VirtualFile>? = null

    /**
     * Show diff starting at the given file.
     *
     * To match Claude Code's navigation UX, this opens a multi-file chain whenever
     * possible, so users get left/right (file) navigation in the same view.
     */
    fun showDiff(entry: DiffEntry) {
        val allEntries = project.service<SessionManager>().getAllDiffEntries()
        val index = allEntries.indexOfFirst { it.diff.file == entry.diff.file }

        if (index >= 0 && allEntries.size > 1) {
            showMultiFileDiff(allEntries, index)
            return
        }

        // Fallback: single diff when there is only one pending change.
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(entry.diff.file)
        val beforeText = entry.diff.before
        val afterText = resolveAfterContent(entry)
        val beforeContent = DiffContentFactory.getInstance().create(project, beforeText, fileType)
        val afterContent = DiffContentFactory.getInstance().create(project, afterText, fileType)

        val request = SimpleDiffRequest(
            "OpenCode Diff: ${entry.diff.file}",
            beforeContent,
            afterContent,
            "Original",
            "OpenCode (Modified)"
        )

        decorateRequest(request, entry)
        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * Show diffs for multiple entries in a chain (allows navigation between files).
     *
     * Aligned with Claude Code:
     * - Default shows the requested index (or the last one in the batch)
     * - Support navigation arrows (<- -> for files, ^ v for changes)
     */
    fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int? = null) {
        // 1. Close previously opened diff window to avoid accumulation
        closeLastOpenedDiff()
        
        val total = entries.size
        val requests = entries.mapIndexed { index, entry ->
            val fileType = FileTypeManager.getInstance()
                .getFileTypeByFileName(entry.diff.file)
            
            // 简化标题：只显示文件名，去掉 "(1 of N)" 这种让人困惑的计数
            // Diff 窗口自带导航，用户只需专注当前文件
            val title = entry.diff.file

            // 获取 diff 内容，如果 after 为空则从磁盘读取（服务端对中文文件名可能返回空内容）
            val beforeText = entry.diff.before
            val afterText = resolveAfterContent(entry)

            val request = SimpleDiffRequest(
                title,
                DiffContentFactory.getInstance().create(project, beforeText, fileType),
                DiffContentFactory.getInstance().create(project, afterText, fileType),
                "Original",
                "Modified (OpenCode)"
            )
            decorateRequest(request, entry)
            request
        }

        val chain = SimpleDiffRequestChain(requests)
        
        // Aligned with Claude Code experience: 
        // If no index provided (e.g. initial auto-open), show the FIRST file to reflect chronological order ("replay thought process").
        // If index provided (e.g. from ToolWindow or Auto-advance), use that index.
        val targetIndex = initialIndex ?: 0
        if (targetIndex in requests.indices) {
            chain.index = targetIndex
        }

        // 2. Capture the newly opened diff file
        val connection = project.messageBus.connect()
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                // Heuristic: The file opened immediately after showDiff is likely our diff window
                // Diff files typically have internal types like DiffVirtualFile or ChainDiffVirtualFile
                val isDiffFile = file.javaClass.name.contains("DiffVirtualFile") || 
                                 file.javaClass.name.contains("ChainDiffVirtualFile")
                
                if (isDiffFile) {
                    logger.debug("[OpenCode] Captured opened diff file: ${file.name}")
                    lastOpenedDiffFile = WeakReference(file)
                    connection.disconnect()
                }
            }
        })

        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
        
        // Ensure listener is cleaned up if no file was opened (e.g. error or reuse)
        ApplicationManager.getApplication().invokeLater {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Ignore if already disconnected
            }
        }
    }
    
    private fun closeLastOpenedDiff() {
        val file = lastOpenedDiffFile?.get()
        if (file != null && file.isValid) {
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).closeFile(file)
            }
        }
        lastOpenedDiffFile = null
    }

    /**
     * 获取 diff 的 after 内容。     * 如果服务端返回为空但 additions > 0（服务端 Bug），则从磁盘读取文件内容作为回退。
     */
    private fun resolveAfterContent(entry: DiffEntry): String {
        val afterText = entry.diff.after
        
        // 如果 after 有内容，直接返回
        if (afterText.isNotEmpty()) {
            return afterText
        }
        
        // 如果 after 为空但 additions > 0，说明服务端没有正确返回内容，尝试从磁盘读取
        if (entry.diff.additions > 0) {
            logger.info("[OpenCode] Server returned empty 'after' for ${entry.diff.file} (additions=${entry.diff.additions}), loading from disk")
            val absolutePath = resolveAbsolutePath(entry.diff.file)
            if (absolutePath != null) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
                if (virtualFile != null && virtualFile.exists()) {
                    try {
                        val diskContent = VfsUtilCore.loadText(virtualFile)
                        logger.info("[OpenCode] Loaded ${diskContent.length} chars from disk for: ${entry.diff.file}")
                        return diskContent
                    } catch (e: Exception) {
                        logger.warn("[OpenCode] Failed to load file from disk: ${entry.diff.file}", e)
                    }
                }
            }
        }
        
        return afterText
    }

    /**
     * 将相对路径转换为绝对路径。
     */
    private fun resolveAbsolutePath(filePath: String): String? {
        val raw = filePath.trim()
        if (raw.isEmpty()) return null

        val basePath = project.basePath
        return try {
            val path = Paths.get(raw)
            val resolved = when {
                path.isAbsolute -> path
                basePath != null -> Paths.get(basePath).resolve(path)
                else -> return null
            }
            resolved.normalize().toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun decorateRequest(request: SimpleDiffRequest, entry: DiffEntry) {
        val actions = listOf(
            AcceptFromDiffEditorAction(entry.diff.file),
            RejectFromDiffEditorAction(entry.diff.file)
        )

        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)
    }
}

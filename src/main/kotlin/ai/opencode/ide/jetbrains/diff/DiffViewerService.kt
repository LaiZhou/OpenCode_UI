package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Service for showing diffs using JetBrains native DiffManager.
 */
@Service(Service.Level.PROJECT)
open class DiffViewerService(private val project: Project) : Disposable {

    companion object {
        private const val MAX_AUTO_OPEN_FILE_CHARS = 1_048_576
        private const val MAX_AUTO_OPEN_TOTAL_CHARS = 3_145_728

        private fun formatChars(chars: Int): String =
            String.format(Locale.ROOT, "%.1fMB", chars / (1024.0 * 1024.0))
    }

    private val logger = Logger.getInstance(DiffViewerService::class.java)
    private var lastOpenedDiffFile: WeakReference<VirtualFile>? = null

    /**
     * Show diffs for multiple entries in a chain.
     * Note: entries should have resolvedBefore pre-populated on a background thread.
     */
    open fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int? = null) {
        if (entries.isEmpty()) {
            logger.info("[DiffViewer] No entries to show")
            return
        }

        val largestEntry = entries.maxByOrNull { maxOf(it.beforeContent.length, it.diff.after.length) }
        val largestChars = largestEntry?.let { maxOf(it.beforeContent.length, it.diff.after.length) } ?: 0
        val totalChars = entries.sumOf { (it.beforeContent.length + it.diff.after.length).toLong() }
        if (largestChars > MAX_AUTO_OPEN_FILE_CHARS || totalChars > MAX_AUTO_OPEN_TOTAL_CHARS) {
            val details = if (largestChars > MAX_AUTO_OPEN_FILE_CHARS) {
                "${largestEntry?.file} (${formatChars(largestChars)})"
            } else {
                "total ${formatChars(totalChars.toInt())} across ${entries.size} files"
            }
            logger.warn("[DiffViewer] Skipping auto-open: $details exceeds the ${formatChars(MAX_AUTO_OPEN_FILE_CHARS)} per-file / ${formatChars(MAX_AUTO_OPEN_TOTAL_CHARS)} total limit")
            if (entries.none { it.diff.after.isNotEmpty() }) {
                logger.info("[DiffViewer] Nothing to review: every changed file was deleted")
                return
            }
            notifyDiffTooLarge(details, entries, initialIndex)
            return
        }

        openDiffs(entries, initialIndex)
    }

    private fun openDiffs(entries: List<DiffEntry>, initialIndex: Int?) {
        closeLastOpenedDiff()

        val total = entries.size
        val requests = entries.mapIndexed { index, entry ->
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(entry.file)
            val title = "[${index + 1}/$total] ${entry.file}"
            val afterTitle = if (entry.hasUserEdits) "Modified (OpenCode + User)" else "Modified (OpenCode)"

            // Use pre-resolved before content (no LocalHistory call on EDT)
            val beforeText = entry.beforeContent
            val afterText = entry.diff.after

            val request = SimpleDiffRequest(
                title,
                DiffContentFactory.getInstance().create(project, beforeText, fileType),
                DiffContentFactory.getInstance().create(project, afterText, fileType),
                "Original",
                afterTitle
            )
            
            // Add Accept/Reject actions
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(
                AcceptFromDiffEditorAction(entry.file),
                RejectFromDiffEditorAction(entry.file)
            ))
            
            request
        }

        val chain = SimpleDiffRequestChain(requests)
        chain.index = (initialIndex ?: 0).coerceIn(0, requests.lastIndex)

        // Track the opened diff file for cleanup
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (file.javaClass.name.contains("DiffVirtualFile")) {
                    lastOpenedDiffFile = WeakReference(file)
                    connection.disconnect()
                }
            }
        })

        logger.info("[DiffViewer] Showing ${entries.size} files")
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)

        // Cleanup listener if not triggered
        ApplicationManager.getApplication().invokeLater {
            try { connection.disconnect() } catch (_: Exception) {}
        }
    }

    private fun notifyDiffTooLarge(details: String, entries: List<DiffEntry>, initialIndex: Int?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("OpenCode")
            .createNotification(
                "OpenCode",
                "Diff not opened automatically: $details. Review it with git (git diff) or open the file manually — opening very large diffs freezes the editor while folding and highlighting run.",
                NotificationType.WARNING
            )
        notification.addAction(NotificationAction.createSimple("Open anyway") {
            openDiffs(entries, initialIndex)
        })
        notification.notify(project)
    }

    private fun closeLastOpenedDiff() {
        lastOpenedDiffFile?.get()?.takeIf { it.isValid }?.let { file ->
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).closeFile(file)
            }
        }
        lastOpenedDiffFile = null
    }

    override fun dispose() {
        closeLastOpenedDiff()
    }
}

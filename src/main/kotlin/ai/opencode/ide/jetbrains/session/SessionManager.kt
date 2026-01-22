package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SessionManager::class.java)

    private var apiClient: OpenCodeApiClient? = null
    private var activeSessionId: String? = null
    private val diffsByFile = ConcurrentHashMap<String, DiffEntry>()
    private var baselineLabel: Label? = null
    private var isCurrentlyBusy = false
    private val openCodeEditedFiles = ConcurrentHashMap<String, Boolean>()
    private val userEditedFiles = ConcurrentHashMap<String, Boolean>()

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!isCurrentlyBusy) return
            val cmd = CommandProcessor.getInstance().currentCommandName
            if (!cmd.isNullOrEmpty()) {
                FileDocumentManager.getInstance().getFile(event.document)?.let { 
                    val path = PathUtil.relativizeToProject(project, it.path)
                    userEditedFiles[path] = true
                }
            }
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
    }

    fun setApiClient(client: OpenCodeApiClient) { apiClient = client }

    /**
     * @return true if the state actually changed to Busy (Idle -> Busy)
     */
    fun onSessionStatusChanged(sessionId: String, status: SessionStatusType): Boolean {
        activeSessionId = sessionId
        val wasBusy = isCurrentlyBusy
        isCurrentlyBusy = status.isBusy()

        if (isCurrentlyBusy && !wasBusy) {
            openCodeEditedFiles.clear()
            userEditedFiles.clear()
            baselineLabel = LocalHistory.getInstance().putSystemLabel(project, "OpenCode Modified Before")
            logger.info("[OpenCode] Turn started: Clear edit lists and create baseline")
            return true
        }
        return false
    }

    fun onFileEdited(filePath: String) {
        val rel = PathUtil.relativizeToProject(project, filePath)
        openCodeEditedFiles[rel] = true
        logger.info("[OpenCode] Scoped edit: $rel")
    }

    fun onDiffReceived(batch: DiffBatch) {
        batch.diffs.forEach { diff ->
            diffsByFile[diff.file] = DiffEntry(batch.sessionId, batch.messageId, null, diff, System.currentTimeMillis())
        }
    }

    fun filterNewDiffs(diffs: List<FileDiff>): List<FileDiff> {
        val edited = openCodeEditedFiles.keys
        
        // If we have explicit edit events, use them as a strict filter.
        if (edited.isNotEmpty()) {
            val result = diffs.filter { it.file in edited }
            logger.info("[OpenCode] Filtered ${result.size} diffs via explicit file.edited events")
            return result
        }

        // If no edit events, check for "actual" discrepancies (Safety Fallback)
        val discrepancies = diffs.filter { isActualDiscrepancy(it) }
        if (discrepancies.isNotEmpty()) {
            logger.warn("[OpenCode] No file.edited events but found ${discrepancies.size} discrepancies")
            notifyMissingEditEvents(discrepancies.size)
        }
        return discrepancies
    }

    private fun isActualDiscrepancy(diff: FileDiff): Boolean {
        val disk = readDiskContent(diff.file)
        
        // 1. If disk already matches "after", it's a stale residue
        if (disk == diff.after || (disk == null && diff.after.isEmpty())) return false
        
        // 2. If disk still matches "before", there's no actual change on disk yet
        val before = resolveBeforeContent(diff.file, diff.before)
        if (disk == before) return false
        
        return true
    }

    private fun notifyMissingEditEvents(count: Int) {
        NotificationGroupManager.getInstance().getNotificationGroup("OpenCode Notifications")
            ?.createNotification("OpenCode", "Detected $count background change(s) without edit events.", NotificationType.INFORMATION)
            ?.notify(project)
    }

    fun acceptDiff(entry: DiffEntry) {
        try {
            val cmd = GeneralCommandLine("git", "add", entry.diff.file).withWorkDirectory(project.basePath)
            val handler = OSProcessHandler(cmd)
            handler.startNotify()
            handler.waitFor(5000)
        } catch (_: Exception) {}
        diffsByFile.remove(entry.diff.file)
    }

    fun rejectDiff(entry: DiffEntry) {
        val abs = toAbsolutePath(entry.diff.file) ?: return
        val before = resolveBeforeContent(entry.diff.file, entry.diff.before)
        ApplicationManager.getApplication().invokeLater {
            runWriteAction {
                try {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(abs)
                    if (vf != null) {
                        if (before.isEmpty() && entry.diff.before.isEmpty()) vf.delete(this)
                        else VfsUtil.saveText(vf, before)
                    } else if (before.isNotEmpty()) {
                        val f = java.io.File(abs)
                        f.parentFile?.mkdirs()
                        f.writeText(before)
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                    }
                    LocalHistory.getInstance().putSystemLabel(project, "OpenCode Rejected ${entry.diff.file}")
                } catch (_: Exception) {}
            }
        }
        diffsByFile.remove(entry.diff.file)
    }

    fun resolveBeforeContent(path: String, serverBefore: String): String {
        return loadLocalHistoryContent(path) ?: serverBefore
    }

    private fun loadLocalHistoryContent(path: String): String? {
        val label = baselineLabel ?: return null
        val abs = toAbsolutePath(path) ?: return null
        return try {
            val content = label.getByteContent(abs) ?: return null
            String(content.bytes, Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun refreshActiveSession() {
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                apiClient?.getSessions(project.basePath!!)?.maxByOrNull { it.time?.updated ?: 0.0 }?.let { activeSessionId = it.id }
            } catch (_: Exception) {}
        }
    }

    fun onSessionCompleted(sId: String, count: Int) { logger.info("[OpenCode] Session $sId turn complete ($count diffs)") }
    fun getDiffForFile(path: String): DiffEntry? = diffsByFile[PathUtil.relativizeToProject(project, path)]
    fun getAllDiffEntries(): List<DiffEntry> = diffsByFile.values.toList()
    fun clearDiffs() { diffsByFile.clear() }
    fun clear() { clearDiffs(); openCodeEditedFiles.clear(); userEditedFiles.clear(); baselineLabel = null; activeSessionId = null; isCurrentlyBusy = false }
    override fun dispose() { clear(); apiClient = null }
    private fun toAbsolutePath(p: String): String? = PathUtil.resolveProjectPath(project, p)
    private fun readDiskContent(p: String): String? { val f = java.io.File(toAbsolutePath(p) ?: return null); return if (f.exists() && !f.isDirectory) f.readText() else null }
    fun hasUserEdits(p: String): Boolean = userEditedFiles.containsKey(p)
}

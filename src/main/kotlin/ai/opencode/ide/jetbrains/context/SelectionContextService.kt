package ai.opencode.ide.jetbrains.context

import ai.opencode.ide.jetbrains.OpenCodeService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Service that tracks and shares selection context to OpenCode.
 * 
 * This service monitors:
 * - File tab changes (which file is currently active)
 * - Editor selection changes (which lines are selected)
 * 
 * Context sharing modes:
 * 1. **On-demand**: User triggers sharing via keyboard shortcut (existing behavior)
 * 2. **Auto-prefill**: Context is automatically prefilled when focusing OpenCode terminal
 * 3. **Status tracking**: Always tracks current context for status bar display
 * 
 * Behavior aligned with Claude Code JetBrains plugin:
 * - Current selection/tab is tracked and ready to share
 * - Uses @path#Lstart-end format for references
 */
@Service(Service.Level.PROJECT)
class SelectionContextService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SelectionContextService::class.java)

    companion object {
        // Debounce delay for context updates
        private const val DEBOUNCE_DELAY_MS = 300L
        
        // Minimum lines in selection to include line numbers
        private const val MIN_SELECTION_LINES = 1
    }

    private val debounceAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    // Track editors we've added listeners to
    private val trackedEditors = ConcurrentHashMap.newKeySet<Editor>()
    
    // Current context (thread-safe)
    private val currentContext = AtomicReference<SelectionContext?>(null)
    
    // Context change listeners
    private val contextListeners = ConcurrentHashMap.newKeySet<(SelectionContext?) -> Unit>()
    
    // Initialization flag
    private var isInitialized = false

    /**
     * Data class representing the current selection context.
     */
    data class SelectionContext(
        val file: VirtualFile,
        val relativePath: String,
        val startLine: Int?,      // null if no selection
        val endLine: Int?,        // null if no selection
        val hasSelection: Boolean
    ) {
        /**
         * Build the context reference string.
         * Format: @path or @path#Lstart-end
         */
        fun toReference(): String {
            return if (hasSelection && startLine != null && endLine != null) {
                "@$relativePath#L$startLine-$endLine"
            } else {
                "@$relativePath"
            }
        }
        
        /**
         * Get a display string for UI.
         */
        fun toDisplayString(): String {
            return if (hasSelection && startLine != null && endLine != null) {
                "$relativePath:$startLine-$endLine"
            } else {
                relativePath
            }
        }
    }

    /**
     * Initialize the service and start tracking context.
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        
        logger.info("SelectionContextService initialized for project: ${project.name}")
        
        // Listen for file editor changes
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    onFileTabChanged(event.newFile)
                }
            }
        )
        
        // Listen for new editors
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    if (isEditorForProject(editor)) {
                        addSelectionListener(editor)
                    }
                }
                
                override fun editorReleased(event: EditorFactoryEvent) {
                    trackedEditors.remove(event.editor)
                }
            },
            this
        )
        
        // Track existing editors
        EditorFactory.getInstance().allEditors.forEach { editor ->
            if (isEditorForProject(editor)) {
                addSelectionListener(editor)
            }
        }
        
        // Initialize with current file
        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            updateContext(editor)
        }
    }

    /**
     * Get the current selection context.
     */
    fun getCurrentContext(): SelectionContext? = currentContext.get()

    /**
     * Get the current context as a reference string (or null if none).
     */
    fun getCurrentReference(): String? = currentContext.get()?.toReference()

    /**
     * Share the current context to OpenCode terminal.
     * Returns true if successfully shared.
     */
    fun shareCurrentContext(): Boolean {
        val context = currentContext.get() ?: return false
        val reference = context.toReference()

        project.service<OpenCodeService>().focusOrCreateTerminalAndPaste("$reference ")
        return true
    }

    /**
     * Add a listener for context changes.
     */
    fun addContextListener(listener: (SelectionContext?) -> Unit) {
        contextListeners.add(listener)
        // Immediately notify with current context
        listener(currentContext.get())
    }

    /**
     * Remove a context change listener.
     */
    fun removeContextListener(listener: (SelectionContext?) -> Unit) {
        contextListeners.remove(listener)
    }

    // ========== Internal Methods ==========

    private fun isEditorForProject(editor: Editor): Boolean {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        val editorProject = editor.project ?: return false
        return editorProject == project && file.isInLocalFileSystem
    }

    private fun addSelectionListener(editor: Editor) {
        if (!trackedEditors.add(editor)) return
        
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                scheduleContextUpdate(editor)
            }
        }, this)
    }

    private fun onFileTabChanged(newFile: VirtualFile?) {
        if (newFile == null) {
            setContext(null)
            return
        }
        
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null && FileDocumentManager.getInstance().getFile(editor.document) == newFile) {
            scheduleContextUpdate(editor)
        } else {
            // No editor, just track the file
            val context = SelectionContext(
                file = newFile,
                relativePath = getRelativePath(newFile),
                startLine = null,
                endLine = null,
                hasSelection = false
            )
            setContext(context)
        }
    }

    private fun scheduleContextUpdate(editor: Editor) {
        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest({
            updateContext(editor)
        }, DEBOUNCE_DELAY_MS.toInt())
    }

    private fun updateContext(editor: Editor) {
        ApplicationManager.getApplication().runReadAction {
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            if (file == null || !file.isInLocalFileSystem) {
                setContext(null)
                return@runReadAction
            }
            
            val selectionModel = editor.selectionModel
            val document = editor.document
            
            val context = if (selectionModel.hasSelection()) {
                val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
                val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
                
                SelectionContext(
                    file = file,
                    relativePath = getRelativePath(file),
                    startLine = startLine,
                    endLine = endLine,
                    hasSelection = endLine - startLine + 1 >= MIN_SELECTION_LINES
                )
            } else {
                SelectionContext(
                    file = file,
                    relativePath = getRelativePath(file),
                    startLine = null,
                    endLine = null,
                    hasSelection = false
                )
            }
            
            setContext(context)
        }
    }

    private fun setContext(context: SelectionContext?) {
        val previous = currentContext.getAndSet(context)
        if (previous != context) {
            notifyListeners(context)
        }
    }

    private fun notifyListeners(context: SelectionContext?) {
        contextListeners.forEach { listener ->
            try {
                listener(context)
            } catch (e: Exception) {
                logger.warn("Context listener error: ${e.message}")
            }
        }
    }

    private fun getRelativePath(file: VirtualFile): String {
        val projectBaseDir = project.basePath ?: return file.name
        val filePath = file.path
        return if (filePath.startsWith(projectBaseDir)) {
            filePath.substring(projectBaseDir.length).removePrefix("/")
        } else {
            file.name
        }
    }

    override fun dispose() {
        debounceAlarm.cancelAllRequests()
        trackedEditors.clear()
        contextListeners.clear()
        currentContext.set(null)
        isInitialized = false
    }
}

/**
 * Project startup activity to initialize SelectionContextService.
 */
class SelectionContextStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<SelectionContextService>().initialize()
    }
}

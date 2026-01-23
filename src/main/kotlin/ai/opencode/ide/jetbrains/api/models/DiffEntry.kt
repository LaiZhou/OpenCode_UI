package ai.opencode.ide.jetbrains.api.models

/**
 * Represents a pending diff for a single file.
 *
 * Lifecycle:
 * 1. Created when Server produces a diff (via SSE or API)
 * 2. Displayed in DiffViewer
 * 3. Removed when user Accepts or Rejects
 *
 * @property file Relative path from project root (always normalized with forward slashes)
 * @property diff The actual file diff content
 * @property hasUserEdits True if user also edited this file during the AI turn
 * @property resolvedBefore The resolved "before" content (from LocalHistory or server).
 *                          Must be set on background thread before showing diff.
 */
data class DiffEntry(
    val file: String,
    val diff: FileDiff,
    val hasUserEdits: Boolean = false,
    val resolvedBefore: String? = null,
    val isCreatedExplicitly: Boolean = false
) {
    /** 
     * True if this is a newly created file. 
     * Must satisfy BOTH:
     * 1. VFS detected a creation event (physical creation).
     * 2. Server Diff says there was no content before (logical creation).
     * This prevents "Replace" operations (Delete+Create) from being treated as New Files,
     * ensuring Reject restores the original content instead of deleting the file.
     */
    val isNewFile: Boolean get() = isCreatedExplicitly && diff.before.isEmpty()
    
    /** Get the before content: prefer resolved, fallback to server-provided */
    val beforeContent: String get() = resolvedBefore ?: diff.before
}

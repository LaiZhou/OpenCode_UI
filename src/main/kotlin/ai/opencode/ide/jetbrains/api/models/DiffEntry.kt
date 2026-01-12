package ai.opencode.ide.jetbrains.api.models

/**
 * Represents a diff entry with full context for operations.
 *
 * Each DiffEntry contains:
 * - The file diff data (before/after content)
 * - The session ID that produced this diff
 * - The message ID for revert operations (resolved from message.part.updated)
 * - Optional part ID for granular revert
 *
 * This allows proper tracking and operations on individual diffs.
 */
data class DiffEntry(
    val sessionId: String,
    val messageId: String?,
    val partId: String?,
    val diff: FileDiff,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Unique key for this diff entry.
     */
    val key: String
        get() = "$sessionId:${diff.file}"

    /**
     * Check if this diff can be reverted (has messageId).
     */
    fun canRevert(): Boolean = messageId != null
}

/**
 * Container for a batch of diffs from a single session/message.
 *
 * When OpenCode produces diffs, they come as a batch per session.
 * Message IDs are resolved from message.part.updated events when available.
 */
data class DiffBatch(
    val sessionId: String,
    val messageId: String?,
    val diffs: List<FileDiff>,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Convert to individual DiffEntry list.
     */
    fun toEntries(): List<DiffEntry> {
        return diffs.map { diff ->
            DiffEntry(
                sessionId = sessionId,
                messageId = messageId,
                partId = null,  // Will be set if we get part-level info
                diff = diff,
                timestamp = timestamp
            )
        }
    }

    /**
     * Get summary statistics.
     */
    fun getSummary(): DiffBatchSummary {
        return DiffBatchSummary(
            sessionId = sessionId,
            messageId = messageId,
            fileCount = diffs.size,
            totalAdditions = diffs.sumOf { it.additions },
            totalDeletions = diffs.sumOf { it.deletions }
        )
    }
}

/**
 * Summary of a diff batch for display.
 */
data class DiffBatchSummary(
    val sessionId: String,
    val messageId: String?,
    val fileCount: Int,
    val totalAdditions: Int,
    val totalDeletions: Int
) {
    override fun toString(): String {
        return "$fileCount file(s), +$totalAdditions -$totalDeletions"
    }
}

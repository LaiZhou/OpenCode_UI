package ai.opencode.ide.jetbrains.api.models

/**
 * Represents an OpenCode session.
 */
data class Session(
    val id: String,
    val projectID: String,
    val directory: String,
    val title: String,
    val parentID: String? = null,
    val summary: SessionSummary? = null,
    val time: SessionTime? = null
)

/**
 * Session time information.
 */
data class SessionTime(
    val updated: Double
)

/**
 * Summary information for a session.
 */
data class SessionSummary(
    val additions: Int,
    val deletions: Int,
    val files: Int,
    val diffs: List<FileDiff>? = null
)

/**
 * Session status from /session/status endpoint.
 * Used to identify the currently active (busy) session.
 */
data class SessionStatus(
    val sessionID: String,
    val status: SessionStatusType
)

/**
 * Session status type.
 */
data class SessionStatusType(
    val type: String,  // "idle", "busy", or "retry"
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
) {
    fun isBusy(): Boolean = type == "busy"
    fun isIdle(): Boolean = type == "idle"
    fun isRetry(): Boolean = type == "retry"
}

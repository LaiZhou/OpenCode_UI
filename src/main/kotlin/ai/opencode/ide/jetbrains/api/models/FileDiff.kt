package ai.opencode.ide.jetbrains.api.models

data class FileDiff(
    val file: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int
)

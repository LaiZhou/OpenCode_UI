package ai.opencode.ide.jetbrains.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

import java.nio.file.Paths

object PathUtil {
    fun toSystemIndependentPath(path: String): String {
        return FileUtil.toSystemIndependentName(path)
    }

    fun resolveProjectPath(project: Project, filePath: String): String? {
        return resolveToSystemIndependentPath(project.basePath, filePath)
    }

    fun resolveToSystemIndependentPath(basePath: String?, filePath: String): String? {
        var raw = filePath.trim()
        if (raw.isEmpty()) return null

        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length - 1)
        }

        val normalizedInput = FileUtil.toSystemIndependentName(raw)
        return try {
            val path = Paths.get(normalizedInput)
            val resolved = when {
                path.isAbsolute -> path
                basePath != null -> Paths.get(basePath).resolve(path)
                else -> return null
            }
            toSystemIndependentPath(resolved.normalize().toString())
        } catch (_: Exception) {
            null
        }
    }

    fun relativizeToProject(project: Project, filePath: String): String {
        return relativizeToProject(project.basePath, filePath)
    }

    fun relativizeToProject(basePath: String?, filePath: String): String {
        if (basePath == null) return toSystemIndependentPath(filePath)

        val normalizedBase = ensureTrailingSlash(toSystemIndependentPath(basePath))
        val normalizedPath = toSystemIndependentPath(filePath)

        return if (normalizedPath.startsWith(normalizedBase)) {
            normalizedPath.substring(normalizedBase.length)
        } else {
            normalizedPath
        }
    }

    private fun ensureTrailingSlash(path: String): String {
        return if (path.endsWith("/")) path else "$path/"
    }
}

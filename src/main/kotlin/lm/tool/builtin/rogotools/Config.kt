package org.thingai.app.aigateway.lm.tool.builtin.rogotools

import org.thingai.app.aigateway.utils.EnvConfig
import java.io.File

internal const val TAG = "RogoTool"

/**
 * Resolves the rogodocs directory.
 *
 * Priority:
 * 1. `ROGO_DOCS_DIR` environment variable (absolute or relative path)
 * 2. `./rogodocs` — sibling of the working directory (next to the running JAR)
 *
 * The directory is created automatically if it does not exist.
 */
fun rogoDocsDir(): File {
    val override = EnvConfig["ROGO_DOCS_DIR"]?.trim()
    val dir = if (!override.isNullOrBlank()) File(override) else File("rogodocs")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

/**
 * Returns `true` if [name] is a safe plain filename:
 * - No path traversal (`..`)
 * - No path separators (`/`, `\`)
 * - Does not start with `.` (no hidden files)
 * - Contains only letters, digits, and `-_. ` characters
 */
fun isSafeFilename(name: String): Boolean {
    if (name.isBlank()) return false
    if (name.contains("..") || name.contains("/") || name.contains("\\")) return false
    if (name.startsWith(".")) return false
    return name.all { it.isLetterOrDigit() || it in "-_. " }
}

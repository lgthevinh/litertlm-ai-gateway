package org.thingai.app.aigateway.lm.tool.builtin

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.GatewayOpenApiToolSet
import org.thingai.app.aigateway.utils.EnvConfig
import org.thingai.base.log.ILog
import java.io.File

// ── Tool set ──────────────────────────────────────────────────────────────────

class RogoToolSet : GatewayOpenApiToolSet {
    override val name  = "rogo"
    override val tools = listOf(RogoListTool(), RogoReadTool())
}

// ── rogo_list ─────────────────────────────────────────────────────────────────

class RogoListTool : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "rogo_list",
          "description": "List all available enterprise documents with their total line counts. Call this first to discover what documents are available before reading any of them.",
          "parameters": {
            "type": "object",
            "properties": {},
            "required": []
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val dir = docsDir()
            if (!dir.isDirectory) {
                return """{"error":"docs directory not found at '${dir.absolutePath}'"}""".trimIndent()
            }

            val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (files.isEmpty()) {
                return """{"docs":[],"count":0,"message":"No documents found in docs directory."}""".trimIndent()
            }

            val entries = files.joinToString(",") { f ->
                val lines = f.bufferedReader().use { it.lines().count() }
                """{"name":"${f.name}","lines":$lines}"""
            }

            ILog.d(TAG, "rogo_list: ${files.size} document(s)")
            """{"docs":[$entries],"count":${files.size}}""".trimIndent()
        } catch (e: Exception) {
            ILog.e(TAG, "rogo_list: ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}""".trimIndent()
        }
    }
}

// ── rogo_read ─────────────────────────────────────────────────────────────────

class RogoReadTool : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "rogo_read",
          "description": "Read lines from an enterprise document. Use 'offset' and 'limit' to page through large documents without loading the entire file at once. Get the total line count from rogo_list first.",
          "parameters": {
            "type": "object",
            "properties": {
              "filename": {
                "type": "string",
                "description": "The exact filename to read, e.g. 'hr-policy.md'. Must match a name returned by rogo_list."
              },
              "offset": {
                "type": "integer",
                "description": "0-based line index to start reading from. Default 0 (beginning of file)."
              },
              "limit": {
                "type": "integer",
                "description": "Maximum number of lines to return. Default 200, max 2000."
              }
            },
            "required": ["filename"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params   = JsonParser.parseString(paramsJsonString).asJsonObject
            val filename = params.get("filename")?.asString?.trim()
            val offset   = params.get("offset")?.asInt?.coerceAtLeast(0) ?: 0
            val limit    = params.get("limit")?.asInt?.coerceIn(1, 2000) ?: 200

            if (filename.isNullOrBlank()) {
                return """{"error":"missing required parameter 'filename'"}""".trimIndent()
            }
            if (!isSafeFilename(filename)) {
                return """{"error":"invalid filename '$filename' — only plain filenames are allowed, no path separators"}""".trimIndent()
            }

            val dir  = docsDir()
            val file = File(dir, filename)

            if (!file.exists() || !file.isFile) {
                return """{"error":"file '$filename' not found — use rogo_list to see available documents"}""".trimIndent()
            }
            if (!file.canonicalPath.startsWith(dir.canonicalPath)) {
                return """{"error":"access denied"}""".trimIndent()
            }

            val allLines   = file.readLines(Charsets.UTF_8)
            val totalLines = allLines.size
            val slice      = allLines.drop(offset).take(limit)

            val escaped = slice.joinToString("\n")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t")

            val returnedLines = slice.size
            val nextOffset    = offset + returnedLines
            val hasMore       = nextOffset < totalLines

            ILog.d(TAG, "rogo_read: '$filename' offset=$offset limit=$limit returned=$returnedLines total=$totalLines")
            """{"filename":"$filename","content":"$escaped","offset":$offset,"limit":$limit,"returned_lines":$returnedLines,"total_lines":$totalLines,"has_more":$hasMore,"next_offset":$nextOffset}""".trimIndent()
        } catch (e: Exception) {
            ILog.e(TAG, "rogo_read: ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}""".trimIndent()
        }
    }
}

// ── shared helpers ────────────────────────────────────────────────────────────

private const val TAG = "RogoTool"

private fun docsDir(): File {
    val override = EnvConfig["ROGO_DOCS_DIR"]?.trim()
    val dir = if (!override.isNullOrBlank()) File(override) else File("rogodocs")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun isSafeFilename(name: String): Boolean {
    if (name.isBlank()) return false
    if (name.contains("..") || name.contains("/") || name.contains("\\")) return false
    if (name.startsWith(".")) return false
    return name.all { it.isLetterOrDigit() || it in "-_. " }
}

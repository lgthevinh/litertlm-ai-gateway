package org.thingai.app.aigateway.lm.tool.builtin.rogotools

import org.thingai.app.aigateway.lm.tool.GatewayOpenApiTool
import org.thingai.base.log.ILog

/**
 * Lists all documents available in the rogodocs directory.
 *
 * Tool name: `rogo_list_docs`
 *
 * Returns a JSON array of file entries — name, size in bytes, and last modified
 * timestamp — so the model knows what is available before calling [RogoReadDocTool].
 */
class RogoListDocsTool : GatewayOpenApiTool {

    override val name = "rogo_list_docs"

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "rogo_list_docs",
          "description": "List all available enterprise documents in the docs directory. Returns file names, sizes, and last-modified dates. Call this first to discover available documents before reading one.",
          "parameters": {
            "type": "object",
            "properties": {},
            "required": []
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val dir = rogoDocsDir()
            if (!dir.isDirectory) {
                return """{"error": "docs directory not found at '${dir.absolutePath}'"}"""
            }

            val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (files.isEmpty()) {
                return """{"docs": [], "count": 0, "message": "No documents found in docs directory."}"""
            }

            val entries = files.joinToString(",\n    ") { f ->
                val sizeKb = "%.1f".format(f.length() / 1024.0)
                """{"name": "${f.name}", "size_bytes": ${f.length()}, "size_kb": $sizeKb, "last_modified": "${java.util.Date(f.lastModified())}"}"""
            }

            """{"docs": [
    $entries
  ], "count": ${files.size}}"""
        } catch (e: Exception) {
            ILog.e(TAG, "rogo_list_docs: ${e.message}")
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }
}

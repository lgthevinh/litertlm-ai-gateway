package org.thingai.app.aigateway.lm.tool.builtin.rogotools

import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.GatewayTool
import org.thingai.base.log.ILog
import java.io.File

/**
 * Reads the full content of a document from the rogodocs directory.
 *
 * Tool name: `rogo_read_doc`
 *
 * The model should call [RogoListDocsTool] first to discover available files,
 * then pass the exact filename here.
 *
 * Path traversal is prevented — only plain filenames are accepted.
 */
class RogoReadDocTool : GatewayTool {

    override val name = "rogo_read_doc"

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "rogo_read_doc",
          "description": "Read the full content of an enterprise document by filename. Use rogo_list_docs first to see available files. Returns the raw text content of the document.",
          "parameters": {
            "type": "object",
            "properties": {
              "filename": {
                "type": "string",
                "description": "The exact filename to read, e.g. hr-policy.md or onboarding.txt. Must match a name returned by rogo_list_docs."
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
                ?: return """{"error": "missing required parameter 'filename'"}"""

            if (!isSafeFilename(filename)) {
                return """{"error": "invalid filename '$filename' — only plain filenames are allowed, no path separators"}"""
            }

            val dir  = rogoDocsDir()
            val file = File(dir, filename)

            if (!file.exists() || !file.isFile) {
                return """{"error": "file '$filename' not found — call rogo_list_docs to see available documents"}"""
            }

            // Canonical path check — extra safety against symlink traversal
            if (!file.canonicalPath.startsWith(dir.canonicalPath)) {
                return """{"error": "access denied"}"""
            }

            val content = file.readText(Charsets.UTF_8)
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "")
                .replace("\t", "\\t")

            """{"filename": "$filename", "content": "$escaped", "size_bytes": ${file.length()}}"""
        } catch (e: Exception) {
            ILog.e(TAG, "rogo_read_doc: ${e.message}")
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }
}

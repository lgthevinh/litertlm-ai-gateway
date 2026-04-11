package org.thingai.app.aigateway.lm.tool.builtin

import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.GatewayTool
import org.thingai.base.log.ILog


/**
 * Returns the current date and time in a configurable format.
 *
 * Tool name: `datetime`
 */


/**
 * Provides access to bundled project documentation for the model.
 *
 * Tool name: `docs`
 *
 */

class LMServiceTool : GatewayTool {

    override val name = "litertlm-docs"

    private val tag = "LMServiceTool"
    private val resourceDir = "docs"

    /**
     * Manifest of all doc file names (without .md extension) bundled in resources/docs/.
     * Update this list whenever a file is added or removed from that directory.
     */
    private val DOC_NAMES = listOf(
        "api-manual",
        "litertlm-sdk",
        "project-overview",
        "tool-system"
    )

    // ---------------------------------------------------------------------------
    // Tool schema
    // ---------------------------------------------------------------------------

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "litertlm-docs",
          "description": "Access technical documentation for the LiteRTLM AI Gateway project — a Kotlin/JVM on-device LLM inference gateway. Use this tool when the user asks about how this project works, its API, architecture, tool system, authentication, configuration, or the LiteRTLM SDK. Call action='list' first to see all available documents, then action='get' with a specific doc name to read the full content.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "description": "The action to perform: 'list' to get an index of all available docs, 'get' to retrieve a specific doc's full content."
              },
              "name": {
                "type": "string",
                "description": "The doc name to retrieve (required when action='get'). Use the name value returned by action='list'."
              }
            },
            "required": ["action"]
          }
        }
    """.trimIndent()

    // ---------------------------------------------------------------------------
    // Execute
    // ---------------------------------------------------------------------------

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonParser.parseString(paramsJsonString).asJsonObject
            val action = params.get("action")?.asString?.trim()
                ?: return """{"error": "missing required parameter 'action'"}"""

            when (action) {
                "list" -> executeList()
                "get"  -> {
                    val docName = params.get("name")?.asString?.trim()
                        ?: return """{"error": "missing required parameter 'name' for action='get'"}"""
                    executeGet(docName)
                }
                else -> """{"error": "unknown action '$action'. Valid actions: 'list', 'get'"}"""
            }
        } catch (e: Exception) {
            ILog.e(tag, "execute failed: ${e.message}")
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    private fun executeList(): String {
        val entries = DOC_NAMES.mapNotNull { docName ->
            val stream = classLoader().getResourceAsStream("$resourceDir/$docName.md")
            if (stream == null) {
                ILog.w(tag, "executeList: manifest entry '$docName' not found in resources — skipping")
                return@mapNotNull null
            }
            val content = stream.bufferedReader().use { it.readText() }
            val (title, description) = extractMeta(docName, content)
            """    {"name": "${docName.escapeJson()}", "title": "${title.escapeJson()}", "description": "${description.escapeJson()}"}"""
        }

        if (entries.isEmpty()) return """{"docs": []}"""
        return "{\n  \"docs\": [\n${entries.joinToString(",\n")}\n  ]\n}"
    }

    private fun executeGet(docName: String): String {
        // Prevent path traversal
        if (docName.contains("..") || docName.contains("/") || docName.contains("\\")) {
            return """{"error": "invalid doc name '$docName'"}"""
        }

        if (docName !in DOC_NAMES) {
            return """{"error": "doc '$docName' not found. Use action='list' to see available docs."}"""
        }

        val stream = classLoader().getResourceAsStream("$resourceDir/$docName.md")
            ?: run {
                ILog.w(tag, "executeGet: resource '$resourceDir/$docName.md' not found in JAR")
                return """{"error": "doc '$docName' not found."}"""
            }

        val content = stream.bufferedReader().use { it.readText() }
        return """{"name": "${docName.escapeJson()}", "content": "${content.escapeJson()}"}"""
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun classLoader() = LMServiceTool::class.java.classLoader

    /**
     * Extracts (title, description) from markdown text.
     * - title       — text of the first `#` heading, or [fallbackName] if none found
     * - description — first non-blank, non-heading line after the title, or empty string
     */
    private fun extractMeta(fallbackName: String, content: String): Pair<String, String> {
        var title = fallbackName
        var description = ""
        var titleFound = false

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            if (!titleFound && trimmed.startsWith("#")) {
                title = trimmed.trimStart('#').trim()
                titleFound = true
                continue
            }

            if (titleFound && !trimmed.startsWith("#")) {
                description = trimmed
                break
            }
        }

        return title to description
    }

    /**
     * Escapes a string for safe embedding inside a JSON double-quoted value.
     */
    private fun String.escapeJson(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", "\\t")
}
package org.thingai.app.aigateway.lm.tool.builtin

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.GatewayOpenApiToolSet
import org.thingai.base.log.ILog
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Tool set ──────────────────────────────────────────────────────────────────

class LMToolSet : GatewayOpenApiToolSet {
    override val name  = "litertlm-docs"
    override val tools: Collection<OpenApiTool> = listOf(LMDocsListTool(), LMDocsGetTool(), DatetimeTool())
}

// ── lm_docs_list ──────────────────────────────────────────────────────────────

class LMDocsListTool : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "lm_docs_list",
          "description": "List all available technical documents for the LiteRTLM AI Gateway project. Call this first to discover document names before reading any of them.",
          "parameters": {
            "type": "object",
            "properties": {},
            "required": []
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val entries = DOC_NAMES.mapNotNull { docName ->
                val stream = classLoader().getResourceAsStream("$RESOURCE_DIR/$docName.md")
                if (stream == null) {
                    ILog.w(TAG, "lm_docs_list: '$docName' not found in resources — skipping")
                    return@mapNotNull null
                }
                val content = stream.bufferedReader().use { it.readText() }
                val (title, description) = extractMeta(docName, content)
                """{"name":"${docName.escapeJson()}","title":"${title.escapeJson()}","description":"${description.escapeJson()}"}"""
            }

            if (entries.isEmpty()) return """{"docs":[]}""".trimIndent()
            """{"docs":[${entries.joinToString(",")}]}""".trimIndent()
        } catch (e: Exception) {
            ILog.e(TAG, "lm_docs_list: ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}""".trimIndent()
        }
    }
}

// ── lm_docs_get ───────────────────────────────────────────────────────────────

class LMDocsGetTool : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "lm_docs_get",
          "description": "Retrieve the full content of a technical document for the LiteRTLM AI Gateway project. Use lm_docs_list first to get available document names.",
          "parameters": {
            "type": "object",
            "properties": {
              "name": {
                "type": "string",
                "description": "The document name to retrieve. Must match a name returned by lm_docs_list."
              }
            },
            "required": ["name"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params  = JsonParser.parseString(paramsJsonString).asJsonObject
            val docName = params.get("name")?.asString?.trim()
                ?: return """{"error":"missing required parameter 'name'"}""".trimIndent()

            if (docName.contains("..") || docName.contains("/") || docName.contains("\\")) {
                return """{"error":"invalid doc name '$docName'"}""".trimIndent()
            }
            if (docName !in DOC_NAMES) {
                return """{"error":"doc '$docName' not found — use lm_docs_list to see available docs"}""".trimIndent()
            }

            val stream = classLoader().getResourceAsStream("$RESOURCE_DIR/$docName.md")
                ?: run {
                    ILog.w(TAG, "lm_docs_get: resource '$RESOURCE_DIR/$docName.md' not found in JAR")
                    return """{"error":"doc '$docName' not found"}""".trimIndent()
                }

            val content = stream.bufferedReader().use { it.readText() }
            """{"name":"${docName.escapeJson()}","content":"${content.escapeJson()}"}""".trimIndent()
        } catch (e: Exception) {
            ILog.e(TAG, "lm_docs_get: ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}""".trimIndent()
        }
    }
}

// datetime tool
class DatetimeTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "datetime",
          "description": "Get the current date and time. Returns a formatted date/time string.",
          "parameters": {
            "type": "object",
            "properties": {
              "format": {
                "type": "string",
                "description": "Java date format pattern, e.g. yyyy-MM-dd HH:mm:ss"
              },
              "timezone": {
                "type": "string",
                "description": "IANA timezone ID, e.g. Asia/Tokyo, America/New_York. Defaults to system timezone."
              }
            },
            "required": []
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonParser.parseString(paramsJsonString).asJsonObject
            val format = params.get("format")?.asString ?: "yyyy-MM-dd HH:mm:ss"
            val tzId   = params.get("timezone")?.asString

            val zone = if (tzId != null) {
                try { ZoneId.of(tzId) }
                catch (_: Exception) { return """{"error": "unknown timezone '$tzId'"}""" }
            } else {
                ZoneId.systemDefault()
            }

            val result = LocalDateTime.now(zone)
                .format(DateTimeFormatter.ofPattern(format))
            """{"datetime": "$result"}""".trimIndent()
        } catch (e: Exception) {
            """{"error": "${e.message?.replace("\"", "'")}"}""".trimIndent()
        }
    }
}


// ── shared helpers ────────────────────────────────────────────────────────────

private const val TAG          = "LMTool"
private const val RESOURCE_DIR = "docs"

private val DOC_NAMES = listOf(
    "api-manual",
    "litertlm-sdk",
    "project-overview",
    "tool-system"
)

private fun classLoader() = LMDocsListTool::class.java.classLoader

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

private fun String.escapeJson(): String = this
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "")
    .replace("\t", "\\t")

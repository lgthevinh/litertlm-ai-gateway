package org.thingai.app.aigateway.lm.tool.builtin

import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.GatewayTool
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Returns the current date and time in a configurable format.
 *
 * Tool name: `datetime`
 */
class DateTimeTool : GatewayTool {

    override val name = "datetime"

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
            """{"datetime": "$result"}"""
        } catch (e: Exception) {
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }
}

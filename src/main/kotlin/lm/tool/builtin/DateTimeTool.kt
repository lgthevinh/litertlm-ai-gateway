package org.thingai.app.aigateway.lm.tool.builtin

import com.google.gson.JsonObject
import org.thingai.app.aigateway.lm.tool.GatewayTool
import org.thingai.app.aigateway.lm.tool.ToolDescriptor
import org.thingai.app.aigateway.lm.tool.ToolParam
import org.thingai.app.aigateway.lm.tool.ToolParamType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Returns the current date and time in a configurable format.
 *
 * Tool name: `datetime`
 *
 * Parameters:
 * - `format` (optional) — Java `DateTimeFormatter` pattern. Defaults to `yyyy-MM-dd HH:mm:ss`.
 * - `timezone` (optional) — IANA timezone ID, e.g. "Asia/Tokyo". Defaults to system timezone.
 */
class DateTimeTool : GatewayTool {

    override val descriptor = ToolDescriptor(
        name = "datetime",
        description = "Get the current date and time. Returns a formatted date/time string.",
        parameters = listOf(
            ToolParam(
                name = "format",
                type = ToolParamType.STRING,
                description = "Date format pattern, e.g. yyyy-MM-dd HH:mm:ss",
                required = false,
                default = "yyyy-MM-dd HH:mm:ss"
            ),
            ToolParam(
                name = "timezone",
                type = ToolParamType.STRING,
                description = "IANA timezone ID, e.g. Asia/Tokyo, America/New_York. Defaults to system timezone.",
                required = false
            )
        )
    )

    override fun execute(params: JsonObject): Any? {
        val format = params.get("format")?.asString ?: "yyyy-MM-dd HH:mm:ss"
        val tzId = params.get("timezone")?.asString

        val zone = if (tzId != null) {
            try {
                ZoneId.of(tzId)
            } catch (_: Exception) {
                return "Error: unknown timezone '$tzId'"
            }
        } else {
            ZoneId.systemDefault()
        }

        return try {
            val now = LocalDateTime.now(zone)
            val formatter = DateTimeFormatter.ofPattern(format)
            now.format(formatter)
        } catch (e: Exception) {
            "Error: invalid date format '$format': ${e.message}"
        }
    }
}

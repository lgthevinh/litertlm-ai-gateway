package org.thingai.app.aigateway.lm.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Describes a tool's identity and parameter schema.
 *
 * [toJsonObject] produces the JSON schema that LiteRTLM feeds to the model
 * so it knows how to call the tool.
 *
 * @param name        Unique tool identifier, e.g. "datetime".
 * @param description What the tool does — the model reads this to decide when to call it.
 * @param parameters  Parameter definitions.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ToolParam> = emptyList()
) {

    /**
     * Builds the JSON schema object expected by `InternalJsonTool.getToolDescription()`.
     *
     * Format:
     * ```json
     * {
     *   "name": "datetime",
     *   "description": "Get the current date and time...",
     *   "parameters": {
     *     "type": "object",
     *     "properties": {
     *       "format": { "type": "string", "description": "..." }
     *     },
     *     "required": ["format"]
     *   }
     * }
     * ```
     */
    fun toJsonObject(): JsonObject {
        val properties = JsonObject()
        val required = JsonArray()

        for (param in parameters) {
            val prop = JsonObject()
            prop.addProperty("type", param.type.jsonSchemaType)
            prop.addProperty("description", param.description)
            properties.add(param.name, prop)

            if (param.required) {
                required.add(param.name)
            }
        }

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", properties)
        params.add("required", required)

        val root = JsonObject()
        root.addProperty("name", name)
        root.addProperty("description", description)
        root.add("parameters", params)
        return root
    }
}

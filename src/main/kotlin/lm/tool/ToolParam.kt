package org.thingai.app.aigateway.lm.tool

/**
 * Describes a single parameter accepted by a [GatewayOpenApiTool].
 *
 * @param name        Parameter name as the model will supply it in the JSON call.
 * @param type        JSON schema type.
 * @param description Human-readable description — included in the tool schema the model reads.
 * @param required    If true, the model must supply this parameter.
 * @param default     Optional default value (used in documentation only — tools check params themselves).
 */
data class ToolParam(
    val name: String,
    val type: ToolParamType,
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)

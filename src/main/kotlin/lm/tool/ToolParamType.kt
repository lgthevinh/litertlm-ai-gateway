package org.thingai.app.aigateway.lm.tool

/**
 * JSON schema type for a tool parameter.
 * Maps directly to the JSON schema `type` field in the tool description.
 */
enum class ToolParamType(val jsonSchemaType: String) {
    STRING("string"),
    INT("integer"),
    FLOAT("number"),
    BOOLEAN("boolean"),
    OBJECT("object")
}

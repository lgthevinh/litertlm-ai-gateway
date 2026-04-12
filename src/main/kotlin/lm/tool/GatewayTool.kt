package org.thingai.app.aigateway.lm.tool

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolSet

/**
 * A gateway-level tool that the model can invoke during inference.
 *
 * Extends [OpenApiTool] so it can be passed directly to the LiteRTLM SDK via
 * the top-level `tool(openApiTool)` function.
 *
 * Implementations must provide:
 * - [name] — unique identifier used in [ToolRegistry] and stored in the DB
 * - [getToolDescriptionJsonString] — OpenAPI-compatible JSON schema the model reads
 * - [execute] — called by the SDK with a JSON params string, must return a JSON string
 *
 * ## Error handling
 * Never throw from [execute]. Return a JSON error string instead:
 * ```json
 * {"error": "missing required parameter 'expression'"}
 * ```
 */
interface GatewayOpenApiTool : OpenApiTool {
    /** Unique name used to look up this tool in [ToolRegistry]. */
    val name: String
}

interface GatewayToolSet : ToolSet {
    val name: String
}
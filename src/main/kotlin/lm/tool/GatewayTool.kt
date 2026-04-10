package org.thingai.app.aigateway.lm.tool

import com.google.gson.JsonObject

/**
 * A gateway-level tool that the model can invoke during inference.
 *
 * Implementations are registered in [ToolRegistry] and bridged to LiteRTLM's
 * native `InternalJsonTool` interface via [GatewayToolProvider].
 *
 * ## Error handling
 *
 * Never throw from [execute]. The SDK calls this synchronously inside
 * `ToolManager.execute()` — an unhandled exception may corrupt inference state.
 * Instead, return a descriptive error string:
 * ```
 * "Error: missing required parameter 'expression'"
 * ```
 * The model receives this as the tool result and can respond accordingly.
 */
interface GatewayTool {

    /** Describes the tool's name, purpose, and parameter schema. */
    val descriptor: ToolDescriptor

    /**
     * Executes the tool with the parameters the model supplied.
     *
     * Called synchronously on the SDK inference thread.
     * Return a [String] for simple results — the SDK serializes it back to the model.
     *
     * @param params JSON object of named parameters as supplied by the model.
     * @return The result to feed back to the model. Typically a plain [String].
     */
    fun execute(params: JsonObject): Any?
}

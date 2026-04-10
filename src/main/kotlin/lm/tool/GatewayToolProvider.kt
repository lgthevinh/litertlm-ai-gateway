package org.thingai.app.aigateway.lm.tool

import com.google.ai.edge.litertlm.InternalJsonTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.gson.JsonObject
import org.thingai.base.log.ILog

/**
 * Bridges a list of [GatewayTool] instances to LiteRTLM's [ToolProvider] contract.
 *
 * Pass an instance of this class in [com.google.ai.edge.litertlm.ConversationConfig.tools]
 * to make the tools available to the model during inference.
 *
 * Each [GatewayTool] is wrapped as an anonymous [InternalJsonTool]:
 * - `getToolDescription()` → [ToolDescriptor.toJsonObject]
 * - `execute(params)`      → [GatewayTool.execute]
 */
class GatewayToolProvider(private val tools: List<GatewayTool>) : ToolProvider() {

    companion object {
        private const val TAG = "GatewayToolProvider"
    }

    fun provideTools(): Map<String, InternalJsonTool> {
        return tools.associate { tool ->
            tool.descriptor.name to object : InternalJsonTool {

                override fun getToolDescription(): JsonObject {
                    return tool.descriptor.toJsonObject()
                }

                override fun execute(params: JsonObject): Any? {
                    return try {
                        ILog.d(TAG, "execute: ${tool.descriptor.name}(${params})")
                        val result = tool.execute(params)
                        ILog.d(TAG, "execute: ${tool.descriptor.name} -> $result")
                        result
                    } catch (e: Exception) {
                        // Safety net — tools should never throw, but if they do, return an error
                        // string rather than letting the exception propagate into the SDK.
                        val msg = "Error: tool '${tool.descriptor.name}' failed: ${e.message}"
                        ILog.e(TAG, msg)
                        msg
                    }
                }
            }
        }
    }
}

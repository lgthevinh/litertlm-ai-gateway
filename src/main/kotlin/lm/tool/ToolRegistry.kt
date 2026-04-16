package org.thingai.app.aigateway.lm.tool

import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import org.thingai.base.log.ILog
import java.util.concurrent.ConcurrentHashMap

object ToolRegistry {

    private const val TAG = "ToolRegistry"

    private val registry = ConcurrentHashMap<String, GatewayOpenApiToolSet>()

    fun register(toolSet: GatewayOpenApiToolSet) {
        registry[toolSet.name] = toolSet
        ILog.i(TAG, "register: '${toolSet.name}'")
    }

    fun unregister(name: String): Boolean {
        val removed = registry.remove(name) != null
        if (removed) ILog.i(TAG, "unregister: '$name'")
        return removed
    }

    /** Returns a toolset by its set name (e.g. "rogo", "litertlm-docs"). */
    fun get(name: String): GatewayOpenApiToolSet? = registry[name]

    fun getToolProviders(names: List<String>): List<ToolProvider> {
        return names.flatMap { name ->
            val toolSet = registry[name]
            if (toolSet != null) return@flatMap toolSet.tools.map { tool(it) }
            ILog.w(TAG, "getToolProviders: '$name' not registered — skipping")
            emptyList()
        }
    }

    /**
     * Finds and executes a tool by its individual tool name (e.g. "rogo_list", "datetime").
     * Searches across all registered toolsets — the agent calls tools by name, not by set.
     *
     * @return The tool result string, or a JSON error string if not found or execution fails.
     */
    fun executeTool(toolName: String, paramsJson: String): String {
        for (toolSet in registry.values) {
            val tool = toolSet.tools.find { openApiTool ->
                runCatching {
                    com.google.gson.JsonParser
                        .parseString(openApiTool.getToolDescriptionJsonString())
                        .asJsonObject
                        .get("name")?.asString == toolName
                }.getOrDefault(false)
            }
            if (tool != null) {
                ILog.d(TAG, "executeTool: '$toolName' found in toolset '${toolSet.name}'")
                return try {
                    tool.execute(paramsJson)
                } catch (e: Exception) {
                    ILog.e(TAG, "executeTool: '$toolName' threw: ${e.message}")
                    """{"error": "${e.message?.replace("\"", "'")}"}"""
                }
            }
        }
        ILog.w(TAG, "executeTool: '$toolName' not found in any registered toolset")
        return """{"error": "tool '$toolName' not found"}"""
    }

    fun listAll(): List<GatewayOpenApiToolSet> = registry.values.sortedBy { it.name }

    fun clear() {
        registry.clear()
        ILog.d(TAG, "clear: all tools removed")
    }
}

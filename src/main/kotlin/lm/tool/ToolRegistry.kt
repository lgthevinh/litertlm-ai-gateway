package org.thingai.app.aigateway.lm.tool

import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import org.thingai.base.log.ILog
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry of available [GatewayTool] instances.
 *
 * Tools are registered at application startup and looked up at config-build time.
 * Thread-safe — backed by [ConcurrentHashMap].
 */
object ToolRegistry {

    private const val TAG = "ToolRegistry"

    private val tools = ConcurrentHashMap<String, GatewayTool>()

    /** Registers a tool. Overwrites any existing tool with the same name. */
    fun register(tool: GatewayTool) {
        tools[tool.name] = tool
        ILog.i(TAG, "register: '${tool.name}' registered")
    }

    /** Unregisters a tool by name. Returns true if it existed. */
    fun unregister(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) ILog.i(TAG, "unregister: '$name' removed")
        return removed
    }

    /** Returns a single [GatewayTool] by name, or null if not registered. */
    fun get(name: String): GatewayTool? = tools[name]

    /**
     * Resolves the given names to registered tools and wraps each with the SDK `tool()` function,
     * returning a [List<ToolProvider>] ready for [com.google.ai.edge.litertlm.ConversationConfig.tools].
     * Unknown names are silently skipped with a warning log.
     */
    fun getToolProviders(names: List<String>): List<ToolProvider> {
        return names.mapNotNull { name ->
            val gatewayTool = tools[name]
            if (gatewayTool == null) {
                ILog.w(TAG, "getToolProviders: tool '$name' not registered — skipping")
                null
            } else {
                tool(gatewayTool)
            }
        }
    }

    /** Returns all registered [GatewayTool] instances sorted by name. */
    fun listAll(): List<GatewayTool> = tools.values.sortedBy { it.name }

    /** Clears all registered tools. Called on [org.thingai.app.aigateway.engine.LMService.stop]. */
    fun clear() {
        tools.clear()
        ILog.d(TAG, "clear: all tools removed")
    }
}

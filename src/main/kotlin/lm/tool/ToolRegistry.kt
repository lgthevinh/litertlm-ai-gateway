package org.thingai.app.aigateway.lm.tool

import org.thingai.base.log.ILog
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry of available [GatewayTool] instances.
 *
 * Tools are registered at application startup (builtin tools in [org.thingai.app.aigateway.engine.LMService.start])
 * and looked up at config-build time when a conversation references tool names.
 *
 * Thread-safe — backed by [ConcurrentHashMap].
 */
object ToolRegistry {

    private const val TAG = "ToolRegistry"

    private val tools = ConcurrentHashMap<String, GatewayTool>()

    /** Registers a tool. Overwrites any existing tool with the same name. */
    fun register(tool: GatewayTool) {
        tools[tool.descriptor.name] = tool
        ILog.i(TAG, "register: '${tool.descriptor.name}' registered")
    }

    /** Unregisters a tool by name. Returns true if the tool existed. */
    fun unregister(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) ILog.i(TAG, "unregister: '$name' removed")
        return removed
    }

    /** Returns a single tool by name, or null if not registered. */
    fun get(name: String): GatewayTool? = tools[name]

    /**
     * Returns all tools matching the given names.
     * Unknown names are silently skipped with a warning log.
     */
    fun getAll(names: List<String>): List<GatewayTool> {
        return names.mapNotNull { name ->
            tools[name] ?: run {
                ILog.w(TAG, "getAll: tool '$name' not registered — skipping")
                null
            }
        }
    }

    /** Returns descriptors for all registered tools. */
    fun list(): List<ToolDescriptor> = tools.values.map { it.descriptor }

    /** Clears all registered tools. Used in tests or on shutdown. */
    fun clear() {
        tools.clear()
        ILog.d(TAG, "clear: all tools removed")
    }
}

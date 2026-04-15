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

    fun get(name: String): GatewayOpenApiToolSet? = registry[name]

    fun getToolProviders(names: List<String>): List<ToolProvider> {
        return names.flatMap { name ->
            val toolSet = registry[name]
            if (toolSet != null) return@flatMap toolSet.tools.map { tool(it) }
            ILog.w(TAG, "getToolProviders: '$name' not registered — skipping")
            emptyList()
        }
    }

    fun listAll(): List<GatewayOpenApiToolSet> = registry.values.sortedBy { it.name }

    fun clear() {
        registry.clear()
        ILog.d(TAG, "clear: all tools removed")
    }
}

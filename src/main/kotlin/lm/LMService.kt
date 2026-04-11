package org.thingai.app.aigateway.lm

import com.google.ai.edge.litertlm.EngineConfig
import org.thingai.app.aigateway.lm.handler.ConversationHandler
import org.thingai.app.aigateway.lm.handler.EngineHandler
import org.thingai.app.aigateway.lm.handler.MessageHandler
import org.thingai.app.aigateway.lm.tool.ToolRegistry
import org.thingai.app.aigateway.lm.tool.builtin.CalculatorTool
import org.thingai.app.aigateway.lm.tool.builtin.DateTimeTool
import org.thingai.app.aigateway.lm.tool.builtin.LMServiceTool
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite

object LMService {

    private const val TAG = "LMService"

    private var engineConfig: EngineConfig? = null
    private var dao: DaoSqlite? = null

    /** Exposed to routes — null until [start] completes successfully. */
    @Volatile
    var conversationHandler: ConversationHandler? = null
        private set

    private var engineHandler: EngineHandler? = null

    // ── Configuration ─────────────────────────────────────────────────────────

    fun setEngineConfig(config: EngineConfig) {
        this.engineConfig = config
    }

    fun setDao(dao: DaoSqlite) {
        this.dao = dao
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialises the engine pool, message handler, and conversation handler.
     *
     * [EngineHandler.start] is async — [onReady] is called once all engines are up.
     * Until then, [conversationHandler] is null and routes return 503.
     *
     * Safe to call again to restart after a failure — stops the previous handler first.
     */
    fun start(onReady: (Boolean) -> Unit = {}) {
        val config = engineConfig ?: run {
            ILog.e(TAG, "start: engineConfig not set")
            onReady(false)
            return
        }
        val db = dao ?: run {
            ILog.e(TAG, "start: dao not set")
            onReady(false)
            return
        }

        // Stop any previous engine handler gracefully
        engineHandler?.stop()
        conversationHandler = null

        val newEngineHandler  = EngineHandler(config)
        val newMessageHandler = MessageHandler(db)

        engineHandler = newEngineHandler

        newEngineHandler.start { success ->
            if (success) {
                // Register builtin tools
                registerBuiltinTools()

                conversationHandler = ConversationHandler(
                    messageHandler = newMessageHandler,
                    engineHandler  = newEngineHandler
                )
                ILog.i(TAG, "start: ready — ${EngineHandler.ENGINE_COUNT} engines online")
            } else {
                ILog.e(TAG, "start: engine init failed")
            }
            onReady(success)
        }
    }

    /**
     * Shuts down the engine pool. [conversationHandler] is set to null.
     * In-flight tasks complete; queued tasks receive an error.
     */
    fun stop() {
        engineHandler?.stop()
        engineHandler       = null
        conversationHandler = null
        ToolRegistry.clear()
        ILog.i(TAG, "stop: engines stopped")
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Registers built-in gateway tools in [ToolRegistry].
     * Called once after engine initialization succeeds.
     */
    private fun registerBuiltinTools() {
        ToolRegistry.register(DateTimeTool())
        ToolRegistry.register(CalculatorTool())
        ToolRegistry.register(LMServiceTool())
        ILog.i(TAG, "registerBuiltinTools: ${ToolRegistry.listAll().size} tools registered")
    }
}

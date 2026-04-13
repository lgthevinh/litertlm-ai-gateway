package org.thingai.app.aigateway.lm

import com.google.ai.edge.litertlm.EngineConfig
import org.thingai.app.aigateway.lm.attachment.AttachmentStore
import org.thingai.app.aigateway.lm.handler.ConversationHandler
import org.thingai.app.aigateway.lm.handler.EngineHandler
import org.thingai.app.aigateway.lm.handler.MessageHandler
import org.thingai.app.aigateway.lm.conversation.ConversationJobRegistry
import org.thingai.app.aigateway.lm.tool.ToolRegistry
import org.thingai.app.aigateway.lm.tool.builtin.DatetimeTool
import org.thingai.app.aigateway.lm.tool.builtin.LMServiceTool
import org.thingai.app.aigateway.lm.tool.builtin.rogotools.RogoListDocsTool
import org.thingai.app.aigateway.lm.tool.builtin.rogotools.RogoReadDocTool
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite
import java.io.File

object LMService {

    private const val TAG = "LMService"

    private var engineConfig: EngineConfig? = null
    private var dao: DaoSqlite? = null

    /** Application data directory — set from [LMApplication.appDir] before [start]. */
    var appDir: File? = null
        private set

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

    fun setAppDir(dir: File) {
        this.appDir = dir
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
        val newAttachmentStore = appDir?.let { AttachmentStore(it) }
        val newMessageHandler = MessageHandler(db, newAttachmentStore)

        engineHandler = newEngineHandler

        newEngineHandler.start { success ->
            if (success) {
                // Register builtin tools
                registerBuiltinTools()

                conversationHandler = ConversationHandler(
                    messageHandler  = newMessageHandler,
                    engineHandler   = newEngineHandler,
                    attachmentStore = newAttachmentStore
                )
                ILog.i(TAG, "start: ready — engine online")
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
        ConversationJobRegistry.clear()
        ILog.i(TAG, "stop: engines stopped")
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Registers built-in gateway tools in [ToolRegistry].
     * Called once after engine initialization succeeds.
     */
    private fun registerBuiltinTools() {
        ToolRegistry.register(DatetimeTool())
        ToolRegistry.register(LMServiceTool())
        ToolRegistry.register(RogoListDocsTool())
        ToolRegistry.register(RogoReadDocTool())
        ILog.i(TAG, "registerBuiltinTools: ${ToolRegistry.listAll().size} tools registered")
    }
}

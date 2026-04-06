package org.thingai.app.aigateway.engine

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.thingai.app.aigateway.callback.RequestCallback
import org.thingai.app.aigateway.engine.handler.ConversationHandler
import org.thingai.app.aigateway.engine.handler.SessionHandler

object LiteRTLMEngine {
    private var engineConfig: EngineConfig? = null
    private var engine: Engine? = null

    var sessionHandler: SessionHandler? = null
    var conversationHandler: ConversationHandler? = null

    fun initEngine(callback: RequestCallback<Boolean>? = null) {
        if (engineConfig == null) {
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                engine?.close()
                engine = Engine(engineConfig!!)
                engine!!.initialize()
                sessionHandler = SessionHandler(engine!!)
                conversationHandler = ConversationHandler(engine!!)
                callback?.onSuccess(true)
            } catch (e: Exception) {
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun setEngineConfig(engineConfig: EngineConfig) {
        this.engineConfig = engineConfig
    }
}
package org.thingai.app.aigateway.engine

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.thingai.app.aigateway.engine.handler.ConversationHandler
import org.thingai.app.aigateway.engine.handler.SessionHandler

object LiteRTLMEngine {
    private var engineConfig: EngineConfig? = null
    private var engine: Engine? = null

    val sessionHandler = SessionHandler()
    val conversationHandler = ConversationHandler()

    fun initEngine() {
        if (engineConfig == null) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            engine?.close()
            engine = Engine(engineConfig!!)
            engine!!.initialize(
            )
        }
    }

    fun setEngineConfig(engineConfig: EngineConfig) {
        this.engineConfig = engineConfig
    }
}
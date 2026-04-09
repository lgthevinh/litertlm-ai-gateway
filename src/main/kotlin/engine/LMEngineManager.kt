package org.thingai.app.aigateway.engine

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.thingai.app.aigateway.callback.RequestCallback
import org.thingai.app.aigateway.engine.handler.ConversationHandler
import org.thingai.base.log.ILog

object LMEngineManager {
    private val TAG = "LMEngineManager"

    private var engineConfig: EngineConfig? = null
    private var engine: Engine? = null

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
                conversationHandler = ConversationHandler(engine!!)

                ILog.d(TAG, "initEngine", "success")
                callback?.onSuccess(true)
            } catch (e: Exception) {
                ILog.d(TAG, "initEngine", "failed: ${e.message}")
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun setEngineConfig(engineConfig: EngineConfig) {
        this.engineConfig = engineConfig
    }
}
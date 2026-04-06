package org.thingai.app.aigateway

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.thingai.app.aigateway.api.route.registerRoute
import org.thingai.app.aigateway.callback.RequestCallback
import org.thingai.app.aigateway.engine.LiteRTLMEngine

fun main() {
    LiteRTLMEngine.setEngineConfig(EngineConfig(
        modelPath = "/data/model/gemma4-e4b/gemma-4-E4B-it.litertlm",
        backend = Backend.CPU()
    ))

    LiteRTLMEngine.initEngine(object: RequestCallback<Boolean> {
        override fun onSuccess(result: Boolean) {
            println("Engine initialized successfully")
        }

        override fun onError(error: String) {
            println("Failed to initialize engine: $error")
        }
    })

    embeddedServer(Netty, port = 8080) {
        registerRoute()
    }.start(wait = true)
}
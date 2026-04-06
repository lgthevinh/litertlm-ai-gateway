package org.thingai.app.aigateway

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.thingai.app.aigateway.engine.LiteRTLMEngine

fun main() {
    embeddedServer(Netty, port = 8080) {

    }.start(wait = true)

    LiteRTLMEngine.setEngineConfig(EngineConfig(
        modelPath = "/data/model/gemma4-e4b/gemma-4-E4B-it.litertlm",
        backend = Backend.CPU()
    ))

    LiteRTLMEngine.initEngine()
}
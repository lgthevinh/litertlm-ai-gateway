package org.thingai.app.aigateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.thingai.app.aigateway.api.route.registerRoute

fun main() {
    val app = LMApplication()
    app.init()

    embeddedServer(Netty, port = 8080) {
        registerRoute()
    }.start(wait = true)
}
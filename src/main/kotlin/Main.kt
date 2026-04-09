package org.thingai.app.aigateway

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import org.thingai.app.aigateway.api.route.registerRoute
import kotlin.time.Duration.Companion.seconds

fun main() {
    LMApplication.init()

    embeddedServer(Netty, port = 8080) {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 60.seconds
        }
        registerRoute()
    }.start(wait = true)
}

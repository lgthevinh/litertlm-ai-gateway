package org.thingai.app.aigateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.thingai.app.aigateway.api.route.registerRoute
import kotlin.io.path.Path

fun main() {
    // print the current working directory
    println("Current working directory: ${Path("").toAbsolutePath()}")

    LMApplication.init()

    embeddedServer(Netty, port = 8080) {
        registerRoute()
    }.start(wait = true)
}
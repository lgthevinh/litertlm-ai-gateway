package org.thingai.app.aigateway.api.route

import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.registerRoute() {
    routing {
        config()
        conversation()
    }
}
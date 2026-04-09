package org.thingai.app.aigateway.api.route

import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.registerRoute() {
    routing {
        // Public — no auth required
        auth()
        config()

        // Protected — AuthPlugin installed inside each extension
        conversation()
        apiKey()
    }
}

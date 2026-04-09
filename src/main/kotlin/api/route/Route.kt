package org.thingai.app.aigateway.api.route

import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.registerRoute() {
    routing {
        // UI — no prefix, no auth
        config()

        route("/api") {
            // Public
            auth()

            // Protected — AuthPlugin / DualAuthPlugin installed inside each extension
            conversation()
            apiKey()
        }

        // WebSocket — separate from /api prefix to keep WS URLs clean
        conversationWebSocket()
    }
}

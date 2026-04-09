package org.thingai.app.aigateway.api.route

import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.config() {
    // Serve the UI — index.html at /, all static assets under /static/
    staticResources("/", "static") {
        default("index.html")
    }

    route("/config") {
        get("/models/current") {
            call.respondText("current model")
        }
    }
}

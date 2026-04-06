package org.thingai.app.aigateway.api.route

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.config() {
    route("/config") {
        get("/models/current") {

        }
    }
}
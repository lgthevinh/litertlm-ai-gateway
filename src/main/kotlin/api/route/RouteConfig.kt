package org.thingai.app.aigateway.api.route

import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.route.dto.ListToolsResponse
import org.thingai.app.aigateway.api.route.dto.ToolDto
import org.thingai.app.aigateway.api.route.dto.ToolParamDto
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.lm.tool.ToolRegistry
import org.thingai.app.aigateway.utils.JsonUtils

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

    route("/api") {
        // GET /api/tools — list all registered tools (public, no auth)
        get("/tools") {
            val tools = ToolRegistry.list().map { desc ->
                ToolDto(
                    name = desc.name,
                    description = desc.description,
                    parameters = desc.parameters.map { param ->
                        ToolParamDto(
                            name = param.name,
                            type = param.type.name,
                            description = param.description,
                            required = param.required
                        )
                    }
                )
            }
            call.respondJson(JsonUtils.toJson(ListToolsResponse(ok = true, tools = tools)))
        }
    }
}

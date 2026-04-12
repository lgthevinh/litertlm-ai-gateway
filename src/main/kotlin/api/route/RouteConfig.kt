package org.thingai.app.aigateway.api.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.lm.LMService
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
        // Returns the OpenAPI schema JSON for each tool directly.
        get("/tools") {
            val arr = JsonArray()
            ToolRegistry.listAll().forEach { gatewayTool ->
                runCatching {
                    JsonParser.parseString(gatewayTool.getToolDescriptionJsonString()).asJsonObject
                }.getOrNull()?.let { arr.add(it) }
            }
            val response = JsonObject().apply {
                addProperty("ok", true)
                add("tools", arr)
            }
            call.respondJson(JsonUtils.toJson(response))
        }

        // GET /api/queue — current inference queue size (public, no auth)
        // Returns { "ok": true, "size": 0 }
        get("/queue") {
            val size = LMService.conversationHandler?.getQueueSize() ?: 0
            val response = JsonObject().apply {
                addProperty("ok", true)
                addProperty("size", size)
            }
            call.respondJson(JsonUtils.toJson(response))
        }
    }
}

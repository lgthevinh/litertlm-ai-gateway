package org.thingai.app.aigateway.api.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.lm.LMService
import org.thingai.app.aigateway.lm.tool.ToolRegistry
import org.thingai.app.aigateway.utils.JsonUtils
import java.io.File

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
            ToolRegistry.listAll().forEach { toolSet ->
                toolSet.tools.forEach { tool ->
                    runCatching {
                        JsonParser.parseString(tool.getToolDescriptionJsonString()).asJsonObject
                    }.getOrNull()?.let { arr.add(it) }
                }
            }
            val response = JsonObject().apply {
                addProperty("ok", true)
                add("tools", arr)
            }
            call.respondJson(JsonUtils.toJson(response))
        }

        // GET /api/queue — current inference queue (public, no auth)
        // Returns { "ok": true, "size": 2, "queue": [{ "name": "...", "position": 1, "status": "processing" }, ...] }
        get("/queue") {
            val handler = LMService.conversationHandler
            val entries = handler?.getQueueEntries() ?: emptyList()
            val arr = JsonArray()
            entries.forEach { entry ->
                arr.add(JsonObject().apply {
                    addProperty("name", entry.name)
                    addProperty("position", entry.position)
                    addProperty("status", entry.status)
                })
            }
            val response = JsonObject().apply {
                addProperty("ok", true)
                addProperty("size", entries.size)
                add("queue", arr)
            }
            call.respondJson(JsonUtils.toJson(response))
        }

        // GET /api/attachments/{convName}/{filename} — serve an attachment file (public, no auth)
        // Used by clients to display images or play audio from conversation history.
        get("/attachments/{convName}/{filename}") {
            val convName = call.parameters["convName"]?.trim().orEmpty()
            val filename = call.parameters["filename"]?.trim().orEmpty()

            if (convName.isBlank() || filename.isBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing parameters")), HttpStatusCode.BadRequest)
                return@get
            }

            // Path traversal safety
            if (convName.contains("..") || filename.contains("..") ||
                convName.contains("/") || convName.contains("\\") ||
                filename.contains("/") || filename.contains("\\")) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid path")), HttpStatusCode.BadRequest)
                return@get
            }

            val appDir = LMService.appDir
            if (appDir == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Service not ready")), HttpStatusCode.ServiceUnavailable)
                return@get
            }

            val file = File(appDir, "attachments/$convName/$filename")

            // Verify the resolved path is inside the attachments directory
            val attachmentsRoot = File(appDir, "attachments").canonicalPath
            if (!file.canonicalPath.startsWith(attachmentsRoot)) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid path")), HttpStatusCode.BadRequest)
                return@get
            }

            if (!file.exists() || !file.isFile) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "File not found")), HttpStatusCode.NotFound)
                return@get
            }

            // Resolve content type from extension
            val contentType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "png"         -> ContentType.Image.PNG
                "gif"         -> ContentType.Image.GIF
                "webp"        -> ContentType("image", "webp")
                "wav"         -> ContentType("audio", "wav")
                "mp3"         -> ContentType("audio", "mpeg")
                "ogg"         -> ContentType("audio", "ogg")
                "m4a"         -> ContentType("audio", "mp4")
                else          -> ContentType.Application.OctetStream
            }

            call.respondBytes(file.readBytes(), contentType)
        }
    }
}

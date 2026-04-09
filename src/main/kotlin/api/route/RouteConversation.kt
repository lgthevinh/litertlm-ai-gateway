package org.thingai.app.aigateway.api.route

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.dto.CreateConversationRequest
import org.thingai.app.aigateway.api.route.dto.CreateConversationResponse
import org.thingai.app.aigateway.api.route.dto.ListConversationsResponse
import org.thingai.app.aigateway.api.route.dto.OkResponse
import org.thingai.app.aigateway.api.route.dto.SendMessageRequest
import org.thingai.app.aigateway.api.route.dto.SendMessageResponse
import org.thingai.app.aigateway.api.plugin.DualAuthPlugin
import org.thingai.app.aigateway.engine.LMEngineManager
import org.thingai.app.aigateway.engine.predefine.BuiltinConversationConfig
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.conversation() {
    route("/conversations") {
        install(DualAuthPlugin)

        // GET /conversations
        // Response 200: { "ok": true, "conversations": ["chat1", "chat2"] }
        get {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@get
            }

            val response = ListConversationsResponse(
                ok            = true,
                conversations = handler.getConversationList()
            )
            call.respondJson(JsonUtils.toJson(response))
        }

        // POST /conversations
        // Body: { "name": "my-chat", "config": "coder" }
        //    or { "name": "my-chat", "systemInstruction": "You are a pirate." }
        // Response 201: { "ok": true, "name": "my-chat", "config": "coder" }
        post {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@post
            }

            val body = call.receiveText().trim()
            if (body.isEmpty()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Request body is required")), HttpStatusCode.BadRequest)
                return@post
            }

            val req = runCatching {
                JsonUtils.fromJson(body, CreateConversationRequest::class.java)
            }.getOrNull()

            val name = req?.name?.trim()?.takeIf { it.isNotBlank() }
            if (name == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing field: name")), HttpStatusCode.BadRequest)
                return@post
            }

            // Resolve ConversationConfig:
            // 1. Explicit systemInstruction overrides everything
            // 2. Named builtin preset ("assistant", "coder", "concise", "creative")
            // 3. Default to ASSISTANT
            val resolvedConfig = req.systemInstruction?.trim()?.takeIf { it.isNotBlank() }?.let { instruction ->
                ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    initialMessages   = emptyList(),
                    samplerConfig     = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
                )
            } ?: presetByName(req.config)

            val configLabel = req.systemInstruction?.trim()?.takeIf { it.isNotBlank() }
                ?.let { "custom" }
                ?: (req.config?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "assistant")

            val created = handler.createConversation(name, resolvedConfig)
            if (!created) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' already exists")), HttpStatusCode.Conflict)
                return@post
            }

            val response = CreateConversationResponse(ok = true, name = name, config = configLabel)
            call.respondJson(JsonUtils.toJson(response), HttpStatusCode.Created)
        }

        // POST /conversations/{name}/messages
        // Body: { "message": "Hello, how are you?" }
        // Response 200: { "ok": true, "reply": "I'm doing well..." }
        post("/{name}/messages") {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@post
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing conversation name")), HttpStatusCode.BadRequest)
                return@post
            }

            val body = call.receiveText().trim()
            if (body.isEmpty()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Request body is required")), HttpStatusCode.BadRequest)
                return@post
            }

            val req = runCatching {
                JsonUtils.fromJson(body, SendMessageRequest::class.java)
            }.getOrNull()

            val message = req?.message?.trim()?.takeIf { it.isNotBlank() }
            if (message == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing field: message")), HttpStatusCode.BadRequest)
                return@post
            }

            val lmResponse = handler.sendMessage(name, message)
            if (lmResponse == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' not found")), HttpStatusCode.NotFound)
                return@post
            }

            val response = SendMessageResponse(ok = true, reply = lmResponse.toString())
            call.respondJson(JsonUtils.toJson(response))
        }

        // DELETE /conversations/{name}
        // Response 200: { "ok": true }
        delete("/{name}") {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@delete
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing conversation name")), HttpStatusCode.BadRequest)
                return@delete
            }

            val closed = handler.closeConversation(name)
            if (!closed) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' not found")), HttpStatusCode.NotFound)
                return@delete
            }

            call.respondJson(JsonUtils.toJson(OkResponse(ok = true)))
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun presetByName(name: String?): ConversationConfig = when (name?.trim()?.lowercase()) {
    "coder"    -> BuiltinConversationConfig.CODER
    "concise"  -> BuiltinConversationConfig.CONCISE
    "creative" -> BuiltinConversationConfig.CREATIVE
    else       -> BuiltinConversationConfig.ASSISTANT
}
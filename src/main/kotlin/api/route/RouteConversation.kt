package org.thingai.app.aigateway.api.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.plugin.DualAuthPlugin
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.dto.CreateConversationRequest
import org.thingai.app.aigateway.api.route.dto.CreateConversationResponse
import org.thingai.app.aigateway.api.route.dto.GetMessagesResponse
import org.thingai.app.aigateway.api.route.dto.ListConversationsResponse
import org.thingai.app.aigateway.api.route.dto.OkResponse
import org.thingai.app.aigateway.api.route.dto.SendMessageRequest
import org.thingai.app.aigateway.api.route.dto.SendMessageResponse
import org.thingai.app.aigateway.api.route.dto.StoredMessageDto
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.engine.LMService
import org.thingai.app.aigateway.lm.handler.WsChunk
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.conversation() {
    route("/conversations") {
        install(DualAuthPlugin)

        // GET /api/conversations
        // Response 200: { "ok": true, "conversations": ["chat1", "chat2"] }
        get {
            val handler = LMService.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@get
            }

            call.respondJson(JsonUtils.toJson(ListConversationsResponse(ok = true, conversations = handler.listConversations())))
        }

        // POST /api/conversations
        // Body: { "name": "my-chat", "config": "coder" }
        //    or { "name": "my-chat", "systemInstruction": "You are a pirate." }
        // Response 201: { "ok": true, "name": "my-chat", "config": "coder" }
        // Response 409: name already exists
        post {
            val handler = LMService.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@post
            }

            val body = call.receiveText().trim()
            if (body.isEmpty()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Request body is required")), HttpStatusCode.BadRequest)
                return@post
            }

            val req = runCatching { JsonUtils.fromJson(body, CreateConversationRequest::class.java) }.getOrNull()

            val name = req?.name?.trim()?.takeIf { it.isNotBlank() }
            if (name == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing field: name")), HttpStatusCode.BadRequest)
                return@post
            }

            val systemInstruction = req.systemInstruction?.trim()?.takeIf { it.isNotBlank() }
            val configLabel = if (systemInstruction != null) {
                "custom"
            } else {
                req.config?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "assistant"
            }

            val tools = req.tools
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            val created = handler.createConversation(
                name              = name,
                systemInstruction = systemInstruction,
                configLabel       = configLabel,
                tools             = tools
            )
            if (!created) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' already exists")), HttpStatusCode.Conflict)
                return@post
            }

            call.respondJson(JsonUtils.toJson(CreateConversationResponse(ok = true, name = name, config = configLabel)), HttpStatusCode.Created)
        }

        // GET /api/conversations/{name}/messages
        // Returns full message history for the conversation, oldest-first.
        // Response 200: { "ok": true, "messages": [{ "role": "user"|"model", "text": "...", "seq": 0, "createdAt": 0 }] }
        get("/{name}/messages") {
            val handler = LMService.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@get
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing conversation name")), HttpStatusCode.BadRequest)
                return@get
            }

            if (!handler.hasConversation(name)) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' not found")), HttpStatusCode.NotFound)
                return@get
            }

            val messages = handler.getHistory(name).map {
                StoredMessageDto(role = it.role, text = it.text, seq = it.seq, createdAt = it.createdAt)
            }
            call.respondJson(JsonUtils.toJson(GetMessagesResponse(ok = true, messages = messages)))
        }

        // POST /api/conversations/{name}/messages
        // Body: { "message": "Hello, how are you?" }
        // Response 200: { "ok": true, "reply": "I'm doing well..." }
        post("/{name}/messages") {
            val handler = LMService.conversationHandler
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

            val req = runCatching { JsonUtils.fromJson(body, SendMessageRequest::class.java) }.getOrNull()
            val message = req?.message?.trim()?.takeIf { it.isNotBlank() }
            if (message == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing field: message")), HttpStatusCode.BadRequest)
                return@post
            }

            // Collect the full streaming reply into a single string
            val replyBuffer = StringBuilder()
            var errorMessage: String? = null

            handler.sendMessage(name, message).collect { chunk ->
                when (chunk) {
                    is WsChunk.Token -> replyBuffer.append(chunk.text)
                    is WsChunk.Error -> errorMessage = chunk.message
                    is WsChunk.Done  -> Unit
                }
            }

            if (errorMessage != null) {
                val status = if (errorMessage.contains("not found", ignoreCase = true))
                    HttpStatusCode.NotFound else HttpStatusCode.InternalServerError
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, errorMessage)), status)
                return@post
            }

            call.respondJson(JsonUtils.toJson(SendMessageResponse(ok = true, reply = replyBuffer.toString())))
        }

        // DELETE /api/conversations/{name}
        // Response 200: { "ok": true }
        // Response 404: conversation not found
        delete("/{name}") {
            val handler = LMService.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@delete
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing conversation name")), HttpStatusCode.BadRequest)
                return@delete
            }

            val deleted = handler.deleteConversation(name)
            if (!deleted) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' not found")), HttpStatusCode.NotFound)
                return@delete
            }

            call.respondJson(JsonUtils.toJson(OkResponse(ok = true)))
        }
    }
}

package org.thingai.app.aigateway.api.route

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.plugin.DualAuthPlugin
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.dto.AttachmentDto
import org.thingai.app.aigateway.api.route.dto.ConversationStateResponse
import org.thingai.app.aigateway.api.route.dto.ConversationSummary
import org.thingai.app.aigateway.api.route.dto.CreateConversationRequest
import org.thingai.app.aigateway.api.route.dto.CreateConversationResponse
import org.thingai.app.aigateway.api.route.dto.GetMessagesResponse
import org.thingai.app.aigateway.api.route.dto.ListConversationsResponse
import org.thingai.app.aigateway.api.route.dto.OkResponse
import org.thingai.app.aigateway.api.route.dto.SendMessageRequest
import org.thingai.app.aigateway.api.route.dto.SendMessageResponse
import org.thingai.app.aigateway.api.route.dto.StoredMessageDto
import org.thingai.app.aigateway.api.route.dto.UpdateConversationRequest
import org.thingai.app.aigateway.api.route.dto.UpdateConversationResponse
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.lm.LMService
import org.thingai.app.aigateway.lm.conversation.ConversationJobRegistry
import org.thingai.app.aigateway.lm.conversation.ConversationWsChunk
import org.thingai.app.aigateway.lm.attachment.AttachmentType
import org.thingai.app.aigateway.lm.attachment.toAttachmentRefs
import org.thingai.app.aigateway.lm.handler.IncomingAttachment
import org.thingai.app.aigateway.utils.JsonUtils
import kotlin.collections.map

fun Route.conversation() {
    route("/conversations") {
        install(DualAuthPlugin)

        // GET /api/conversations
        // Response 200: { "ok": true, "conversations": [{ "name": "chat1", "stateless": false }] }
        get {
            val handler = LMService.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@get
            }

            val summaries = handler.listConversations().map {
                ConversationSummary(
                    name            = it.first,
                    stateless       = it.second,
                    agentMode       = it.third,
                    thinkingEnabled = handler.isThinkingEnabled(it.first)
                )
            }
            call.respondJson(JsonUtils.toJson(ListConversationsResponse(ok = true, conversations = summaries)))
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
                req.config?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "agent"
            }

            val tools = req.tools
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            val created = handler.createConversation(
                name              = name,
                systemInstruction = systemInstruction,
                configLabel       = configLabel,
                tools             = tools,
                stateless         = req.stateless ?: false,
                thinkingEnabled   = req.thinkingEnabled ?: true
            )
            if (!created) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' already exists")), HttpStatusCode.Conflict)
                return@post
            }

            call.respondJson(
                JsonUtils.toJson(CreateConversationResponse(ok = true, name = name, config = configLabel, stateless = req.stateless ?: false)),
                HttpStatusCode.Created
            )
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

            val messages = handler.getHistory(name).map { it ->
                val attachmentDtos = it.attachments.toAttachmentRefs()
                    .map { ref -> AttachmentDto(type = ref.type.name.lowercase(), filename = ref.filename) }
                    .ifEmpty { null }

                StoredMessageDto(
                    role        = it.role,
                    text        = it.text,
                    seq         = it.seq,
                    createdAt   = it.createdAt,
                    attachments = attachmentDtos
                )
            }
            call.respondJson(JsonUtils.toJson(GetMessagesResponse(ok = true, messages = messages)))
        }

        // GET /api/conversations/{name}/state
        // Response 200: { "ok": true, "name": "my-chat", "state": "IDLE"|"BUSY"|"DONE" }
        get("/{name}/state") {
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

            val state = handler.getConversationState(name).name  // "IDLE", "BUSY"
            call.respondJson(JsonUtils.toJson(ConversationStateResponse(ok = true, name = name, state = state)))
        }

        // POST /api/conversations/{name}/messages
        // Accepts either:
        //   - application/json: { "message": "Hello" }
        //   - multipart/form-data: "message" text part + optional "images"/"audio" file parts
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

            // Parse message and attachments based on content type
            var message: String? = null
            var thinkingOverride: Boolean? = null
            val attachments = mutableListOf<IncomingAttachment>()

            val contentType = call.request.contentType()
            if (contentType.match(ContentType.MultiPart.FormData)) {
                // Multipart: extract text + file parts
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "message") {
                                message = part.value.trim()
                            }
                        }
                        is PartData.FileItem -> {
                            @Suppress("DEPRECATION")
                            val bytes = part.streamProvider().readBytes()
                            val ext = part.originalFileName
                                ?.substringAfterLast('.', "bin")
                                ?.lowercase()
                                ?: "bin"

                            when (part.name) {
                                "images" -> attachments += IncomingAttachment(AttachmentType.IMAGE, bytes, ext)
                                "audio"  -> attachments += IncomingAttachment(AttachmentType.AUDIO, bytes, ext)
                            }
                        }
                        else -> Unit
                    }
                    part.dispose()
                }
            } else {
                // JSON fallback (backward compatible)
                val body = call.receiveText().trim()
                if (body.isEmpty()) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Request body is required")), HttpStatusCode.BadRequest)
                    return@post
                }
                val req = runCatching { JsonUtils.fromJson(body, SendMessageRequest::class.java) }.getOrNull()
                message = req?.message?.trim()
                thinkingOverride = req?.thinking
            }

            if (message.isNullOrBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing field: message")), HttpStatusCode.BadRequest)
                return@post
            }

            // Launch detached inference and await completion
            var errorMessage: String? = null
            var reply: String? = null

            handler.sendMessage(name, message!!, attachments, thinkingOverride).collect { chunk ->
                when (chunk) {
                    is ConversationWsChunk.Busy  -> {
                        val busyJob = ConversationJobRegistry.getBusyJob(name)
                        if (busyJob != null) {
                            reply = runCatching { busyJob.completionDeferred.await() }.getOrNull()
                            if (reply == null) errorMessage = "Inference failed"
                        } else {
                            errorMessage = "Inference failed"
                        }
                    }
                    is ConversationWsChunk.Error -> errorMessage = chunk.message
                    else                         -> Unit
                }
            }

            val err = errorMessage
            if (err != null) {
                val status = if (err.contains("not found", ignoreCase = true))
                    HttpStatusCode.NotFound else HttpStatusCode.InternalServerError
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, err)), status)
                return@post
            }

            call.respondJson(JsonUtils.toJson(SendMessageResponse(ok = true, reply = reply ?: "")))
        }

        // PATCH /api/conversations/{name}
        // Body: any subset of { "systemInstruction", "clearSystemInstruction",
        //                        "config", "topK", "topP", "temperature", "tools" }
        // Response 200: { "ok": true, "name": "<name>", "config": "<configLabel>" }
        // Response 404: conversation not found
        patch("/{name}") {
            val handler = LMService.conversationHandler
            if (handler == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Engine not ready")), HttpStatusCode.ServiceUnavailable)
                return@patch
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing conversation name")), HttpStatusCode.BadRequest)
                return@patch
            }

            val body = call.receiveText().trim()
            if (body.isEmpty()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Request body is required")), HttpStatusCode.BadRequest)
                return@patch
            }

            val req = runCatching { JsonUtils.fromJson(body, UpdateConversationRequest::class.java) }.getOrNull()
            if (req == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid request body")), HttpStatusCode.BadRequest)
                return@patch
            }

            if (!handler.hasConversation(name)) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Conversation '$name' not found")), HttpStatusCode.NotFound)
                return@patch
            }

            val tools = req.tools?.map { it.trim() }?.filter { it.isNotBlank() }

            val updated = handler.updateConversation(
                name                   = name,
                systemInstruction      = req.systemInstruction?.trim()?.takeIf { it.isNotBlank() },
                clearSystemInstruction = req.clearSystemInstruction ?: false,
                configLabel            = req.config?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                topK                   = req.topK,
                topP                   = req.topP,
                temperature            = req.temperature,
                tools                  = tools
            )

            // Apply thinkingEnabled toggle separately if provided
            if (updated && req.thinkingEnabled != null) {
                handler.setThinkingEnabled(name, req.thinkingEnabled)
            }

            if (!updated) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Failed to update conversation '$name'")), HttpStatusCode.InternalServerError)
                return@patch
            }

            call.respondJson(JsonUtils.toJson(UpdateConversationResponse(
                ok     = true,
                name   = name,
                config = if (req.systemInstruction != null) "custom"
                         else if (req.clearSystemInstruction == true) req.config ?: "assistant"
                         else req.config ?: "updated"
            )))
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

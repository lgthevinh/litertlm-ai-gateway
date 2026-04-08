package org.thingai.app.aigateway.api.route

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.engine.LMEngineManager

fun Route.conversation() {
    route("/conversations") {
        get {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondText("Engine not ready", status = HttpStatusCode.ServiceUnavailable)
                return@get
            }

            val conversations = handler.getConversationList()
            call.respondText(conversations.joinToString(","))
        }

        post("/{name}") {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondText("Engine not ready", status = HttpStatusCode.ServiceUnavailable)
                return@post
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondText("Missing conversation name", status = HttpStatusCode.BadRequest)
                return@post
            }

            val systemInstruction = call.request.queryParameters["system"]?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "You are a helpful assistant."

            val created = handler.createConversation(
                name,
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    initialMessages = emptyList<Message>(),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8),
                )
            )

            if (!created) {
                call.respondText("Conversation already exists", status = HttpStatusCode.Conflict)
                return@post
            }

            call.respondText("ok")
        }

        post("/{name}/messages") {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondText("Engine not ready", status = HttpStatusCode.ServiceUnavailable)
                return@post
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondText("Missing conversation name", status = HttpStatusCode.BadRequest)
                return@post
            }

            val message = call.receiveText().trim()
            if (message.isBlank()) {
                call.respondText("Message body is empty", status = HttpStatusCode.BadRequest)
                return@post
            }

            val response = handler.sendMessage(name, message)
            if (response == null) {
                call.respondText("Conversation not found", status = HttpStatusCode.NotFound)
                return@post
            }

            call.respondText(response.toString())
        }

        delete("/{name}") {
            val handler = LMEngineManager.conversationHandler
            if (handler == null) {
                call.respondText("Engine not ready", status = HttpStatusCode.ServiceUnavailable)
                return@delete
            }

            val name = call.parameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respondText("Missing conversation name", status = HttpStatusCode.BadRequest)
                return@delete
            }

            val closed = handler.closeConversation(name)
            if (!closed) {
                call.respondText("Conversation not found", status = HttpStatusCode.NotFound)
                return@delete
            }

            call.respondText("ok")
        }
    }
}

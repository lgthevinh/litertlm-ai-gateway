package org.thingai.app.aigateway.api.route

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.api.route.dto.WsBusyFrame
import org.thingai.app.aigateway.api.route.dto.WsDoneFrame
import org.thingai.app.aigateway.api.route.dto.WsErrorFrame
import org.thingai.app.aigateway.api.route.dto.WsIncomingMessage
import org.thingai.app.aigateway.api.route.dto.WsTokenFrame
import org.thingai.app.aigateway.lm.LMService
import org.thingai.app.aigateway.lm.conversation.ConversationJobRegistry
import org.thingai.app.aigateway.lm.conversation.ConversationWsChunk
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.conversationWebSocket() {

    // WS /ws/conversations/{name}
    //
    // Auth: ?token=<accessToken|apiKey> query parameter.
    //
    // Protocol (text frames, JSON):
    //   Client → Server:  { "message": "Hello" }
    //   Server → Client:  { "type": "busy" }                      (inference running, wait)
    //                     { "type": "token", "token": "..." }      (partial token, streamed)
    //                     { "type": "done",  "reply": "..." }      (end of turn, full reply)
    //                     { "type": "error", "error": "..." }      (recoverable error)
    //
    // On connect:
    //   IDLE → normal, wait for client message
    //   BUSY → send busy, stream tokens in child coroutine, await deferred, send done
    //
    // The connection stays open for the full conversation lifetime.
    // Send another message after receiving "done" to continue the conversation.
    webSocket("/ws/conversations/{name}") {
        val name = call.parameters["name"]?.trim().orEmpty()
        if (name.isBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing conversation name"))
            return@webSocket
        }

        // ── Auth ─────────────────────────────────────────────────────────────
        val token = call.request.queryParameters["token"]?.trim().orEmpty()
        if (!resolveIdentity(token)) {
            sendError("Invalid or missing token. Connect with ?token=<accessToken|apiKey>")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        // ── Engine check ─────────────────────────────────────────────────────
        val handler = LMService.conversationHandler
        if (handler == null) {
            sendError("Engine not ready")
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Engine not ready"))
            return@webSocket
        }

        // ── Resume check — handle BUSY state on connect ──────────────────────
        when {
            ConversationJobRegistry.isBusy(name) -> {
                val busyJob = ConversationJobRegistry.getBusyJob(name)
                if (busyJob != null) {
                    sendJson(WsBusyFrame())
                    // Stream tokens in a child coroutine — does not block incoming loop
                    val tokenJob = launch {
                        busyJob.tokenFlow
                            .takeWhile { !busyJob.completionDeferred.isCompleted }
                            .collect { sendJson(WsTokenFrame(token = it)) }
                    }
                    // Await full reply on the main coroutine
                    val reply = runCatching { busyJob.completionDeferred.await() }.getOrNull()
                    tokenJob.cancelAndJoin()
                    if (reply != null) sendJson(WsDoneFrame(reply = reply))
                    else sendError("Inference failed while waiting for result")
                }
                // Fall through to message loop
            }
        }

        // ── Message loop ─────────────────────────────────────────────────────
        for (frame in incoming) {
            if (frame !is Frame.Text) continue

            val text = frame.readText().trim()
            if (text.isBlank()) continue

            val req = runCatching {
                JsonUtils.fromJson(text, WsIncomingMessage::class.java)
            }.getOrNull()

            val message = req?.message?.trim()?.takeIf { it.isNotBlank() }
            if (message == null) {
                sendError("Invalid frame. Expected: { \"message\": \"...\" }")
                continue
            }

            // Launch detached inference — collect the single Busy/Error signal
            handler.sendMessage(name, message).collect { chunk ->
                when (chunk) {
                    is ConversationWsChunk.Busy -> {
                        val busyJob = ConversationJobRegistry.getBusyJob(name)
                        if (busyJob != null) {
                            sendJson(WsBusyFrame())
                            // Stream tokens in a child coroutine — does not block collect{}
                            val tokenJob = launch {
                                busyJob.tokenFlow
                                    .takeWhile { !busyJob.completionDeferred.isCompleted }
                                    .collect { sendJson(WsTokenFrame(token = it)) }
                            }
                            val reply = runCatching { busyJob.completionDeferred.await() }.getOrNull()
                            tokenJob.cancelAndJoin()
                            if (reply != null) sendJson(WsDoneFrame(reply = reply))
                            else sendError("Inference failed")
                        }
                    }
                    is ConversationWsChunk.Error -> sendError(chunk.message)
                    else -> { /* Token/Done not emitted in detached mode */ }
                }
            }
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun resolveIdentity(token: String): Boolean {
    if (token.isBlank()) return false
    return if (token.startsWith("lrtlm_")) {
        LMApplication.apiKeyService.validateApiKey(token)
    } else {
        LMApplication.authService.validateAccessToken(token) != null
    }
}

private suspend fun DefaultWebSocketServerSession.sendJson(obj: Any) {
    send(Frame.Text(JsonUtils.toJson(obj)))
}

private suspend fun DefaultWebSocketServerSession.sendError(message: String) {
    send(Frame.Text(JsonUtils.toJson(WsErrorFrame(error = message))))
}

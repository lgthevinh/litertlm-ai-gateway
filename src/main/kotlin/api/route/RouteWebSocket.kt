package org.thingai.app.aigateway.api.route

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.api.route.dto.WsDoneFrame
import org.thingai.app.aigateway.api.route.dto.WsErrorFrame
import org.thingai.app.aigateway.api.route.dto.WsIncomingMessage
import org.thingai.app.aigateway.api.route.dto.WsTokenFrame
import org.thingai.app.aigateway.engine.LMEngineManager
import org.thingai.app.aigateway.engine.handler.WsChunk
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.conversationWebSocket() {

    // WS /ws/conversations/{name}
    //
    // Auth: ?token=<accessToken|apiKey> query parameter.
    //   JWT:    ?token=eyJ...
    //   ApiKey: ?token=lrtlm_...
    //
    // Protocol (text frames, JSON):
    //   Client → Server:  { "message": "Hello" }
    //   Server → Client:  { "type": "token", "token": "Hello" }   (one per chunk)
    //                     { "type": "done" }                       (end of turn)
    //                     { "type": "error", "error": "..." }      (recoverable error)
    //
    // The connection stays open for the full conversation lifetime.
    // Send another message after receiving "done" to continue the conversation.
    // Close the socket when finished — the server will not close it mid-conversation.
    webSocket("/ws/conversations/{name}") {
        val name = call.parameters["name"]?.trim().orEmpty()
        if (name.isBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing conversation name"))
            return@webSocket
        }

        // ── Auth ─────────────────────────────────────────────────────────────
        // WebSocket upgrades cannot carry an Authorization header in most clients,
        // so the token is passed as a query parameter instead.
        val token = call.request.queryParameters["token"]?.trim().orEmpty()
        if (!resolveIdentity(token)) {
            sendError("Invalid or missing token. Connect with ?token=<accessToken|apiKey>")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        // ── Engine check ─────────────────────────────────────────────────────
        val handler = LMEngineManager.conversationHandler
        if (handler == null) {
            sendError("Engine not ready")
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Engine not ready"))
            return@webSocket
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

            // ── Stream tokens ─────────────────────────────────────────────────
            handler.sendMessageAsync(name, message).collect { chunk ->
                when (chunk) {
                    is WsChunk.Token -> sendJson(WsTokenFrame(token = chunk.text))
                    is WsChunk.Done  -> sendJson(WsDoneFrame())
                    is WsChunk.Error -> sendError(chunk.message)
                }
            }
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Validates a token (JWT or API key) from the `?token=` query parameter.
 * Returns `true` if the credential is accepted.
 */
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

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
import org.thingai.app.aigateway.api.route.dto.WsAgentMaxStepsFrame
import org.thingai.app.aigateway.api.route.dto.WsAgentThinkingFrame
import org.thingai.app.aigateway.api.route.dto.WsAgentToolCallFrame
import org.thingai.app.aigateway.api.route.dto.WsAgentToolResultFrame
import org.thingai.app.aigateway.api.route.dto.WsBusyFrame
import org.thingai.app.aigateway.api.route.dto.WsDoneFrame
import org.thingai.app.aigateway.api.route.dto.WsErrorFrame
import org.thingai.app.aigateway.api.route.dto.WsIncomingMessage
import org.thingai.app.aigateway.api.route.dto.WsQueuedFrame
import org.thingai.app.aigateway.api.route.dto.WsTokenFrame
import org.thingai.app.aigateway.lm.LMService
import org.thingai.app.aigateway.lm.conversation.ConversationJob
import org.thingai.app.aigateway.lm.conversation.ConversationJobRegistry
import org.thingai.app.aigateway.lm.conversation.ConversationWsChunk
import org.thingai.app.aigateway.lm.attachment.AttachmentType
import org.thingai.app.aigateway.lm.handler.IncomingAttachment
import org.thingai.app.aigateway.utils.JsonUtils
import java.util.Base64

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
                    streamJobToClient(busyJob)
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

            // Decode multimodal attachments from base64
            val attachments = mutableListOf<IncomingAttachment>()
            req.images?.forEach { b64 ->
                val (ext, bytes) = parseBase64WithMime(b64)
                attachments += IncomingAttachment(AttachmentType.IMAGE, bytes, ext)
            }
            req.audio?.forEach { b64 ->
                val (ext, bytes) = parseBase64WithMime(b64)
                attachments += IncomingAttachment(AttachmentType.AUDIO, bytes, ext)
            }

            // Launch detached inference — collect the single Busy/Error signal
            handler.sendMessage(name, message, attachments, req.thinking).collect { chunk ->
                when (chunk) {
                    is ConversationWsChunk.Queued -> {
                        sendJson(WsQueuedFrame(position = chunk.position))
                    }
                    is ConversationWsChunk.Busy -> {
                        val busyJob = ConversationJobRegistry.getBusyJob(name)
                        if (busyJob != null) {
                            sendJson(WsBusyFrame())
                            streamJobToClient(busyJob)
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

/**
 * Streams all output from [job] to the WebSocket client — agent steps, tokens, and final done.
 *
 * Launches two child coroutines in parallel:
 * - One collects [ConversationJob.agentFlow] and sends agent frame types (thinking, tool calls, results)
 * - One collects [ConversationJob.tokenFlow] and sends token frames
 *
 * Both coroutines run until [ConversationJob.completionDeferred] completes, then are cancelled.
 * Finally sends a [WsDoneFrame] with the full reply.
 */
private suspend fun DefaultWebSocketServerSession.streamJobToClient(job: ConversationJob) {
    val agentJob = launch {
        job.agentFlow
            .takeWhile { !job.completionDeferred.isCompleted }
            .collect { chunk ->
                when (chunk) {
                    is ConversationWsChunk.AgentThinking ->
                        sendJson(WsAgentThinkingFrame(stepIndex = chunk.stepIndex, text = chunk.text))
                    is ConversationWsChunk.AgentToolCall ->
                        sendJson(WsAgentToolCallFrame(toolName = chunk.toolName, params = chunk.paramsJson))
                    is ConversationWsChunk.AgentToolResult ->
                        sendJson(WsAgentToolResultFrame(toolName = chunk.toolName, result = chunk.result))
                    is ConversationWsChunk.AgentMaxSteps ->
                        sendJson(WsAgentMaxStepsFrame(maxSteps = chunk.maxSteps, lastOutput = chunk.lastOutput))
                    else -> Unit
                }
            }
    }

    val tokenJob = launch {
        job.tokenFlow
            .takeWhile { !job.completionDeferred.isCompleted }
            .collect { sendJson(WsTokenFrame(token = it)) }
    }

    val reply = runCatching { job.completionDeferred.await() }.getOrNull()
    agentJob.cancelAndJoin()
    tokenJob.cancelAndJoin()

    if (reply != null) sendJson(WsDoneFrame(reply = reply))
    else sendError("Inference failed while waiting for result")
}

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

/**
 * Parses a base64 string, optionally with a data-URI prefix.
 *
 * Accepts:
 * - Plain base64: `"iVBORw0KGgo..."` → defaults to the fallback extension
 * - Data URI: `"data:image/png;base64,iVBORw0KGgo..."` → extracts the extension from MIME
 *
 * @return Pair of (extension, decoded bytes).
 */
private fun parseBase64WithMime(input: String): Pair<String, ByteArray> {
    val match = Regex("^data:([^;]+);base64,(.+)$").matchEntire(input.trim())
    return if (match != null) {
        val mime = match.groupValues[1]        // e.g. "image/png", "audio/wav"
        val ext = mime.substringAfter('/').lowercase().take(8)
        ext to Base64.getDecoder().decode(match.groupValues[2])
    } else {
        "bin" to Base64.getDecoder().decode(input.trim())
    }
}

package org.thingai.app.aigateway.api.route.dto

// ── WebSocket conversation frames ─────────────────────────────────────────────
//
// Client  →  Server:  WsIncomingMessage
// Server  →  Client:  WsTokenFrame | WsDoneFrame | WsErrorFrame

/**
 * Frame sent by the client to submit a message.
 * @param message The user's text input.
 */
data class WsIncomingMessage(
    val message: String?
)

/**
 * Streamed token frame sent by the server during inference.
 * Multiple frames are sent per turn, one per partial token.
 * @param token Partial text chunk from the model.
 */
data class WsTokenFrame(
    val type: String = "token",
    val token: String
)

/**
 * Sent once per turn when the model finishes generating.
 */
data class WsDoneFrame(
    val type: String = "done"
)

/**
 * Sent when an error occurs (conversation not found, inference failed, auth rejected, etc.).
 * The connection remains open — the client may retry.
 * @param error Human-readable error description.
 */
data class WsErrorFrame(
    val type: String = "error",
    val error: String
)

package org.thingai.app.aigateway.api.route.dto

// ── WebSocket conversation frames ─────────────────────────────────────────────
//
// Client  →  Server:  WsIncomingMessage
// Server  →  Client:  WsQueuedFrame | WsBusyFrame | WsTokenFrame | WsDoneFrame | WsErrorFrame

/**
 * Frame sent by the client to submit a message.
 */
data class WsIncomingMessage(
    val message: String?
)

/**
 * Sent when the task is queued behind other conversations.
 * [position] is 1-based: 2 = one task ahead, 3 = two ahead, etc.
 * Followed by [WsBusyFrame] when inference actually starts.
 */
data class WsQueuedFrame(
    val type: String = "queued",
    val position: Int
)

/**
 * Sent when the conversation is BUSY (inference running detached).
 * The client should display a "thinking" indicator and wait for token frames.
 */
data class WsBusyFrame(
    val type: String = "busy"
)

/**
 * Sent for each partial token streamed from the model during inference.
 * Clients append these to build the reply incrementally.
 */
data class WsTokenFrame(
    val type: String = "token",
    val token: String
)

/**
 * Sent once per turn when inference completes, carrying the full reply.
 * The client may use this as the authoritative final text (replaces any
 * incrementally assembled token stream).
 */
data class WsDoneFrame(
    val type: String = "done",
    val reply: String = ""
)

/**
 * Sent when an error occurs. The connection remains open — the client may retry.
 */
data class WsErrorFrame(
    val type: String = "error",
    val error: String
)

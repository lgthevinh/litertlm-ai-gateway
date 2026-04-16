package org.thingai.app.aigateway.api.route.dto

// ── WebSocket conversation frames ─────────────────────────────────────────────
//
// Client  →  Server:  WsIncomingMessage
// Server  →  Client:  WsQueuedFrame | WsBusyFrame | WsTokenFrame | WsDoneFrame | WsErrorFrame

/**
 * Frame sent by the client to submit a message.
 *
 * For text-only messages, only [message] is needed.
 * For multimodal messages, include [images] and/or [audio] as base64-encoded strings.
 * Data-URI prefixes (`data:image/png;base64,...`) are supported and will be stripped.
 */
data class WsIncomingMessage(
    val message: String?,
    val images: List<String>? = null,
    val audio: List<String>? = null,
    /**
     * Per-message thinking override.
     * true  = enable thinking for this message only (even if conversation has thinking OFF)
     * false = disable thinking for this message only (even if conversation has thinking ON)
     * null  = use conversation's stored thinkingEnabled setting
     */
    val thinking: Boolean? = null
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

// ── Agent frames ──────────────────────────────────────────────────────────────

/**
 * Sent for each reasoning step — the model's raw thought text for this step.
 * [stepIndex] is 0-based.
 */
data class WsAgentThinkingFrame(
    val type: String = "agent_thinking",
    val stepIndex: Int,
    val text: String
)

/**
 * Sent when the agent decides to call a tool.
 */
data class WsAgentToolCallFrame(
    val type: String = "agent_tool_call",
    val toolName: String,
    val params: String
)

/**
 * Sent when a tool execution completes.
 */
data class WsAgentToolResultFrame(
    val type: String = "agent_tool_result",
    val toolName: String,
    val result: String
)

/**
 * Sent when the agent loop exhausts [maxSteps] without a final answer.
 * [lastOutput] is used as the fallback response.
 */
data class WsAgentMaxStepsFrame(
    val type: String = "agent_max_steps",
    val maxSteps: Int,
    val lastOutput: String
)

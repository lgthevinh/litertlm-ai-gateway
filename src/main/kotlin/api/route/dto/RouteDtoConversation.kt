package org.thingai.app.aigateway.api.route.dto

// ── POST /conversations  ─────────────────────────────────────────────────────

/**
 * Request body for creating a new conversation.
 *
 * @param name             Unique conversation name.
 * @param systemInstruction Custom system prompt. Defaults to the ASSISTANT preset if omitted.
 * @param config           Builtin config preset: "assistant" | "coder" | "concise" | "creative".
 *                         Ignored when [systemInstruction] is provided.
 */
data class CreateConversationRequest(
    val name: String?,
    val systemInstruction: String?,
    val config: String?
)

data class CreateConversationResponse(
    val ok: Boolean,
    val name: String,
    val config: String
)

// ── GET /conversations  ──────────────────────────────────────────────────────

data class ListConversationsResponse(
    val ok: Boolean,
    val conversations: List<String>
)

// ── POST /conversations/{name}/messages  ─────────────────────────────────────

/**
 * Request body for sending a message.
 *
 * @param message The user message text.
 */
data class SendMessageRequest(
    val message: String?
)

data class SendMessageResponse(
    val ok: Boolean,
    val reply: String
)

package org.thingai.app.aigateway.api.route.dto

// ── POST /conversations  ─────────────────────────────────────────────────────

/**
 * Request body for creating a new conversation.
 *
 * @param name             Unique conversation name.
 * @param systemInstruction Custom system prompt. Defaults to the ASSISTANT preset if omitted.
 * @param config           Builtin config preset: "assistant" | "coder" | "concise" | "creative".
 *                         Ignored when [systemInstruction] is provided.
 * @param tools            Optional list of tool names to bind, e.g. ["datetime", "calculator"].
 */
data class CreateConversationRequest(
    val name: String?,
    val systemInstruction: String?,
    val config: String?,
    val tools: List<String>?
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

// ── GET /conversations/{name}/messages  ──────────────────────────────────────

data class StoredMessageDto(
    val role: String,
    val text: String,
    val seq: Int,
    val createdAt: Long
)

data class GetMessagesResponse(
    val ok: Boolean,
    val messages: List<StoredMessageDto>
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

// ── GET /api/tools  ──────────────────────────────────────────────────────────

data class ToolParamDto(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean
)

data class ToolDto(
    val name: String,
    val description: String,
    val parameters: List<ToolParamDto>
)

data class ListToolsResponse(
    val ok: Boolean,
    val tools: List<ToolDto>
)

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

// ── PATCH /conversations/{name}  ─────────────────────────────────────────────

/**
 * Request body for updating an existing conversation.
 * All fields are optional — only non-null values are applied.
 *
 * @param systemInstruction      New custom system prompt.
 * @param clearSystemInstruction Set true to remove the custom system instruction and revert to a preset.
 * @param config                 Builtin preset: "assistant"|"coder"|"concise"|"creative".
 *                               Only applied when [systemInstruction] is null and [clearSystemInstruction] is false.
 * @param topK                   Sampler top-K.
 * @param topP                   Sampler top-P.
 * @param temperature            Sampler temperature.
 * @param tools                  New tool list. Pass empty list `[]` to clear all tools.
 */
data class UpdateConversationRequest(
    val systemInstruction: String?,
    val clearSystemInstruction: Boolean?,
    val config: String?,
    val topK: Int?,
    val topP: Double?,
    val temperature: Double?,
    val tools: List<String>?
)

data class UpdateConversationResponse(
    val ok: Boolean,
    val name: String,
    val config: String
)

// ── GET /conversations/{name}/state ──────────────────────────────────────────

data class ConversationStateResponse(
    val ok: Boolean,
    val name: String,
    val state: String   // "IDLE" | "BUSY" | "DONE"
)

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

package org.thingai.app.aigateway.api.route.dto

// ── POST /sessions  ──────────────────────────────────────────────────────────

/**
 * Request body for creating a new session.
 *
 * @param name   Unique session name.
 * @param config Builtin sampler preset: "assistant" | "coder" | "concise" | "creative".
 *               Defaults to "assistant" when omitted.
 */
data class CreateSessionRequest(
    val name: String?,
    val config: String?
)

data class CreateSessionResponse(
    val ok: Boolean,
    val name: String,
    val config: String
)

// ── GET /sessions  ───────────────────────────────────────────────────────────

data class ListSessionsResponse(
    val ok: Boolean,
    val sessions: List<String>
)

// ── POST /sessions/{name}/generate  ─────────────────────────────────────────

/**
 * Request body for a one-shot generation request.
 *
 * @param prompt The text input for the model.
 *               This is stateless — no conversation history is kept.
 */
data class SessionGenerateRequest(
    val prompt: String?
)

data class SessionGenerateResponse(
    val ok: Boolean,
    val reply: String
)

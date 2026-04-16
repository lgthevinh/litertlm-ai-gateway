package org.thingai.app.aigateway.lm.entity

import org.thingai.base.dao.annotations.DaoColumn
import org.thingai.base.dao.annotations.DaoTable

/**
 * Persisted conversation record.
 *
 * Stores the conversation identity and the config fields needed to
 * reconstruct a [com.google.ai.edge.litertlm.ConversationConfig] on re-open:
 * - [systemInstruction] → `Contents.of(systemInstruction)`
 * - [topK], [topP], [temperature] → `SamplerConfig(topK, topP, temperature)`
 *
 * [initialMessages] is intentionally NOT stored here — it is rebuilt at
 * re-open time from the [LMStoredMessage] history table, unless [stateless]
 * is true in which case no history is loaded and no messages are persisted.
 */
@DaoTable(name = "lm_conversations")
data class LMStoredConversation(

    /** Unique conversation name — used as the primary key and route parameter. */
    @DaoColumn(primaryKey = true)
    var name: String,

    /**
     * Plain-text system instruction extracted from [ConversationConfig.systemInstruction].
     * Null when the builtin preset does not use a custom instruction
     * (the preset label is stored in [configLabel] instead).
     */
    @DaoColumn
    var systemInstruction: String?,

    /**
     * Human-readable label for the config preset used:
     * "assistant" | "coder" | "concise" | "creative" | "agent" | "custom"
     */
    @DaoColumn
    var configLabel: String,

    /** Sampler — top-K tokens to consider. */
    @DaoColumn
    var topK: Int,

    /** Sampler — nucleus sampling probability. */
    @DaoColumn
    var topP: Double,

    /** Sampler — temperature scaling factor. */
    @DaoColumn
    var temperature: Double,

    /**
     * Comma-separated toolset names bound to this conversation, e.g. "litertlm-docs,rogo".
     * Null or blank means no tools.
     */
    @DaoColumn
    var tools: String?,

    /** Unix epoch ms when the conversation was first created. */
    @DaoColumn
    var createdAt: Long,

    /**
     * When true, this conversation operates without memory:
     * - No message history is loaded into [ConversationConfig.initialMessages] — every turn starts fresh.
     * - No user or model messages are persisted to the DB after each turn.
     *
     * Immutable after creation — cannot be changed via PATCH.
     */
    @DaoColumn
    var stateless: Boolean = false,

    /**
     * When true, this conversation uses the gateway-owned multi-step agent loop ([AgentRunner])
     * instead of single-shot inference. The model is called repeatedly, executing tools between
     * steps, until it produces a Final Answer or [maxAgentSteps] is reached.
     *
     * Set to true when [configLabel] is "agent" at creation time.
     */
    @DaoColumn
    var agentMode: Boolean = true,

    /**
     * When true, the agent loop prepends the <|think> token to the system instruction,
     * enabling the model's extended chain-of-thought reasoning before answering.
     * Can be toggled permanently via PATCH or overridden per-message via the WS/REST payload.
     */
    @DaoColumn
    var thinkingEnabled: Boolean = true

) {
    /** No-arg constructor required by DaoSqlite reflection. */
    constructor() : this(
        name              = "",
        systemInstruction = null,
        configLabel       = "agent",
        topK              = 40,
        topP              = 0.95,
        temperature       = 0.8,
        tools             = null,
        createdAt         = 0L,
        stateless         = false,
        agentMode         = true,
        thinkingEnabled   = true
    )
}

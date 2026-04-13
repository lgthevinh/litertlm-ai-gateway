package org.thingai.app.aigateway.lm.entity

import org.thingai.base.dao.annotations.DaoColumn
import org.thingai.base.dao.annotations.DaoTable

/**
 * A single persisted conversation turn (one user or model message).
 *
 * Rows are ordered by [seq] to reconstruct the conversation history in the
 * correct order when re-opening a conversation.
 *
 * Role values: "user" | "model"
 */
@DaoTable(name = "lm_messages")
data class LMStoredMessage(

    /** Unique row identifier (UUID). */
    @DaoColumn(primaryKey = true)
    var id: String,

    /** Foreign key — matches [LMStoredConversation.name]. */
    @DaoColumn
    var conversationName: String,

    /**
     * Message role: "user" or "model".
     * Maps to [com.google.ai.edge.litertlm.Message.user] /
     * [com.google.ai.edge.litertlm.Message.model] factory functions.
     */
    @DaoColumn
    var role: String,

    /** Plain-text content of the message. */
    @DaoColumn
    var text: String,

    /**
     * Comma-separated attachment references for multimodal messages.
     * Format: `"image:0_0.jpg,audio:0_1.wav"` — each entry is `type:filename`.
     * Null for text-only messages.
     */
    @DaoColumn
    var attachments: String? = null,

    /** Monotonically increasing sequence number within the conversation. */
    @DaoColumn
    var seq: Int,

    /** Unix epoch ms when the message was stored. */
    @DaoColumn
    var createdAt: Long

) {
    /** No-arg constructor required by DaoSqlite reflection. */
    constructor() : this(
        id               = "",
        conversationName = "",
        role             = "user",
        text             = "",
        attachments      = null,
        seq              = 0,
        createdAt        = 0L
    )
}

package org.thingai.app.aigateway.lm.conversation

/**
 * Frames emitted during streaming inference, shared across
 * [org.thingai.app.aigateway.lm.handler.EngineHandler],
 * [org.thingai.app.aigateway.lm.handler.ConversationHandler],
 * and WebSocket/REST routes.
 */
sealed class ConversationWsChunk {
    /** A partial token streamed from the model during inference. */
    data class Token(val text: String) : ConversationWsChunk()

    /** Inference completed — no more tokens for this turn. */
    data object Done : ConversationWsChunk()

    /** Inference or routing error. */
    data class Error(val message: String) : ConversationWsChunk()

    /**
     * Inference has been launched as a detached job.
     * The WS handler should await [ConversationJobRegistry]
     * rather than collecting further tokens from this flow.
     */
    data object Busy : ConversationWsChunk()
}

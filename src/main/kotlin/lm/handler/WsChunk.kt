package org.thingai.app.aigateway.lm.handler

/**
 * Frames emitted during streaming inference, shared across
 * [EngineHandler], [ConversationHandler], and WebSocket/REST routes.
 */
sealed class WsChunk {
    /** A partial token streamed from the model during inference. */
    data class Token(val text: String) : WsChunk()

    /** Inference completed — no more tokens for this turn. */
    data object Done : WsChunk()

    /** Inference or routing error. */
    data class Error(val message: String) : WsChunk()
}

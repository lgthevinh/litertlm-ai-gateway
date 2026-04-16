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
     * Task is queued — the engine is busy with another conversation.
     * [position] is 1-based: 2 = one task ahead, 3 = two tasks ahead, etc.
     */
    data class Queued(val position: Int) : ConversationWsChunk()

    /**
     * Inference has been launched as a detached job.
     * The WS handler should await [ConversationJobRegistry]
     * rather than collecting further tokens from this flow.
     */
    data object Busy : ConversationWsChunk()

    // ── Agent chunks ─────────────────────────────────────────────────────────

    /**
     * Agent is reasoning — [text] is the model's raw thought output for this step.
     * [stepIndex] is 0-based.
     */
    data class AgentThinking(val text: String, val stepIndex: Int) : ConversationWsChunk()

    /**
     * Agent decided to call a tool.
     * [toolName] is the tool being called, [paramsJson] is the JSON parameter string.
     */
    data class AgentToolCall(val toolName: String, val paramsJson: String) : ConversationWsChunk()

    /**
     * Tool execution completed.
     * [toolName] is which tool was called, [result] is its output.
     */
    data class AgentToolResult(val toolName: String, val result: String) : ConversationWsChunk()

    /**
     * Agent exhausted [maxSteps] without producing a final answer.
     * [lastOutput] is the last model output — treated as the fallback response.
     */
    data class AgentMaxSteps(val lastOutput: String, val maxSteps: Int) : ConversationWsChunk()
}

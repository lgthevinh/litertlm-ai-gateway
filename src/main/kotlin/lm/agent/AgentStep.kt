package org.thingai.app.aigateway.lm.agent

/**
 * Represents a single step in an agent reasoning loop.
 *
 * The agent loop emits a sequence of these steps as it works through a task:
 *
 *   Thinking → ToolCall → ToolResult → Thinking → ToolCall → ToolResult → FinalAnswer
 *
 * [Thinking] is the raw model output for a step — it may contain the model's
 * reasoning text before it decides to call a tool or give a final answer.
 *
 * [ToolCall] is emitted when the model requests a tool execution.
 * [ToolResult] is emitted after the tool returns its result.
 * [FinalAnswer] is emitted when the model produces its final response — the
 * [text] field contains the full answer to stream to the client.
 */
sealed class AgentStep {

    /** Raw model output for this reasoning step (the model's thought text). */
    data class Thinking(val text: String, val stepIndex: Int) : AgentStep()

    /** Model decided to call a tool. */
    data class ToolCall(
        val toolName: String,
        val paramsJson: String
    ) : AgentStep()

    /** Tool was executed and returned a result. */
    data class ToolResult(
        val toolName: String,
        val result: String
    ) : AgentStep()

    /**
     * Model produced a final answer — no more tool calls.
     * [text] is the complete response to stream to the client.
     * [stepIndex] is the 0-based step number this answer was produced at.
     */
    data class FinalAnswer(
        val text: String,
        val stepIndex: Int
    ) : AgentStep()

    /**
     * Agent loop exhausted [maxSteps] without producing a final answer.
     * [lastOutput] is the last model output — use it as a fallback response.
     */
    data class MaxStepsReached(
        val lastOutput: String,
        val maxSteps: Int
    ) : AgentStep()
}

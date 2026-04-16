package org.thingai.app.aigateway.lm.agent

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.ToolRegistry
import org.thingai.base.log.ILog

/**
 * Orchestrates a multi-step agent loop using forced structured prompts.
 *
 * Every model call is constrained — the model NEVER gets a free-response turn.
 * Each step appends a suffix demanding a single JSON object:
 *
 *   {"tool": "tool_name", "params": {...}}   → execute tool, loop
 *   {"done": true}                           → collect final answer
 *
 * Step 0: user message + tool-selection suffix (combined — one call)
 * Step N: "Observation: <result>" + tool-selection suffix (one call)
 * Final:  "Give your complete answer." (one call, streamed as response)
 */
class AgentRunner(
    private val conversation: Conversation,
    private val toolRegistry: ToolRegistry,
    private val maxSteps: Int = 8,
    private val onStep: suspend (AgentStep) -> Unit
) {

    companion object {
        private const val TAG = "AgentRunner"

        private val TOOL_SELECTION_SUFFIX = """

Based on the above, what is the NEXT tool you need to call?

Reply ONLY with a single JSON object — no explanation, no markdown, no extra text:

If you need to call a tool:
{"tool": "tool_name", "params": {"param1": "value1"}}

If you have gathered enough information to give a complete answer:
{"done": true}"""

        private const val FINAL_ANSWER_PROMPT =
            "You now have all the information needed. Give your complete, well-formatted answer to the user's original request."
    }

    /**
     * Runs the agent loop.
     *
     * @param userMessage The raw user message text — used to build the first constrained prompt.
     * @param initialContents The full [Contents] object (may include images/audio) used for step 0.
     */
    suspend fun run(userMessage: String, initialContents: Contents): AgentStep {
        var lastOutput   = ""
        // Pending observation from the previous tool call.
        // On the next step it becomes the prompt prefix instead of a separate model call.
        var pendingObservation: String? = null

        for (stepIndex in 0 until maxSteps) {
            ILog.d(TAG, "run: step $stepIndex / $maxSteps")

            // Build constrained prompt — never a free-response turn
            val promptText = when {
                stepIndex == 0 -> {
                    // Step 0: user message text + selection suffix in one call
                    "$userMessage$TOOL_SELECTION_SUFFIX"
                }
                pendingObservation != null -> {
                    // Subsequent steps: observation + selection suffix
                    "Observation: $pendingObservation$TOOL_SELECTION_SUFFIX"
                }
                else -> {
                    // Safety fallback (shouldn't happen)
                    "Based on the conversation so far,$TOOL_SELECTION_SUFFIX"
                }
            }
            pendingObservation = null

            val buffer = StringBuilder()
            conversation.sendMessageAsync(Contents.of(Content.Text(promptText)))
                .collect { buffer.append(it.toString()) }

            val output = buffer.toString().trim()
            lastOutput = output
            ILog.d(TAG, "run: step $stepIndex output: ${output.take(200)}")
            onStep(AgentStep.Thinking(output, stepIndex))

            val decision = parseDecision(output)

            when {
                decision == null -> {
                    ILog.w(TAG, "run: step $stepIndex — no valid JSON found, going to final answer")
                    return collectFinalAnswer(stepIndex, lastOutput)
                }
                decision.has("done") && decision.get("done").asBoolean -> {
                    ILog.i(TAG, "run: step $stepIndex — done signal")
                    return collectFinalAnswer(stepIndex, lastOutput)
                }
                decision.has("tool") -> {
                    val toolName   = decision.get("tool").asString.trim()
                    val paramsJson = decision.getAsJsonObject("params")?.toString() ?: "{}"

                    onStep(AgentStep.ToolCall(toolName, paramsJson))
                    ILog.i(TAG, "run: step $stepIndex → tool=$toolName")

                    val result = toolRegistry.executeTool(toolName, paramsJson)
                    onStep(AgentStep.ToolResult(toolName, result))
                    ILog.d(TAG, "run: step $stepIndex result ${result.length} chars")

                    // Queue as pending — will be prepended on next step, no extra model call
                    pendingObservation = result
                }
                else -> {
                    ILog.w(TAG, "run: step $stepIndex — unrecognised shape, going to final answer")
                    return collectFinalAnswer(stepIndex, lastOutput)
                }
            }
        }

        ILog.w(TAG, "run: maxSteps=$maxSteps exhausted")
        val fallback = AgentStep.MaxStepsReached(lastOutput = lastOutput, maxSteps = maxSteps)
        onStep(fallback)
        return fallback
    }

    private suspend fun collectFinalAnswer(stepIndex: Int, fallbackText: String): AgentStep {
        val buffer = StringBuilder()
        conversation.sendMessageAsync(Contents.of(Content.Text(FINAL_ANSWER_PROMPT)))
            .collect { buffer.append(it.toString()) }

        val answer = buffer.toString().trim().ifBlank { fallbackText }
        ILog.i(TAG, "collectFinalAnswer: ${answer.length} chars")
        val step = AgentStep.FinalAnswer(text = answer, stepIndex = stepIndex)
        onStep(step)
        return step
    }

    /**
     * Finds the first valid JSON object in [raw], stripping markdown fences.
     */
    private fun parseDecision(raw: String): JsonObject? {
        val stripped = raw
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val start = stripped.indexOf('{')
        val end   = stripped.lastIndexOf('}')
        if (start == -1 || end <= start) return null

        return try {
            JsonParser.parseString(stripped.substring(start, end + 1)).asJsonObject
        } catch (_: Exception) {
            ILog.w(TAG, "parseDecision: invalid JSON: ${stripped.take(100)}")
            null
        }
    }
}

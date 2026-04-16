package org.thingai.app.aigateway.lm.agent

import org.thingai.base.log.ILog

/**
 * Parses raw model output from a single agent step into a structured [AgentStep].
 *
 * Expected ReAct format from the model:
 *
 *   Thought: I need to check the current time.
 *   Action: datetime
 *   Action Input: {"timezone": "Asia/Tokyo"}
 *
 * Or a final answer:
 *
 *   Thought: I now have all the information I need.
 *   Final Answer: The current time in Tokyo is 14:35.
 *
 * Parsing is intentionally lenient — the model may not always follow the exact
 * format, so we fall back to [AgentStep.FinalAnswer] if no tool call is detected.
 */
object StepParser {

    private const val TAG = "StepParser"

    // Matches "Action: tool_name" — stops before any colon to avoid matching "Action Input:"
    private val ACTION_REGEX = Regex(
        """(?i)^action\s*:\s*([^:\n]+?)\s*$""",
        RegexOption.MULTILINE
    )

    // Matches "Action Input: {...}" — JSON object, possibly multiline
    private val ACTION_INPUT_REGEX = Regex(
        """(?i)^action\s*input\s*:\s*(\{[\s\S]*?\})""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
    )

    // Matches "Final Answer: ..." — captures everything after the marker
    private val FINAL_ANSWER_REGEX = Regex(
        """(?i)final\s*answer\s*:\s*([\s\S]+)""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Parses [output] (raw model text for one step) into an [AgentStep].
     *
     * Resolution order:
     * 1. If "Action:" and "Action Input:" are both present → [AgentStep.ToolCall]
     * 2. If "Final Answer:" is present → [AgentStep.FinalAnswer] with extracted text
     * 3. Otherwise → [AgentStep.FinalAnswer] with full output (model deviated from format)
     */
    fun parse(output: String, stepIndex: Int): AgentStep {
        val trimmed = output.trim()

        // 1. Check for tool call — both Action and Action Input must be present
        val actionMatch      = ACTION_REGEX.find(trimmed)
        val actionInputMatch = ACTION_INPUT_REGEX.find(trimmed)

        if (actionMatch != null && actionInputMatch != null) {
            val toolName   = actionMatch.groupValues[1].trim()
            val paramsJson = actionInputMatch.groupValues[1].trim()

            ILog.d(TAG, "parse: step=$stepIndex → ToolCall(tool=$toolName)")
            return AgentStep.ToolCall(toolName = toolName, paramsJson = paramsJson)
        }

        // 2. Check for final answer marker
        val finalMatch = FINAL_ANSWER_REGEX.find(trimmed)
        if (finalMatch != null) {
            val answer = finalMatch.groupValues[1].trim()
            ILog.d(TAG, "parse: step=$stepIndex → FinalAnswer (via marker, ${answer.length} chars)")
            return AgentStep.FinalAnswer(text = answer, stepIndex = stepIndex)
        }

        // 3. No recognised structure — treat the whole output as the final answer
        // This handles models that answer directly without following ReAct format
        ILog.w(TAG, "parse: step=$stepIndex → FinalAnswer (fallback — no ReAct markers found)")
        return AgentStep.FinalAnswer(text = trimmed, stepIndex = stepIndex)
    }
}

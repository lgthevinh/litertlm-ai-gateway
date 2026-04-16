package org.thingai.app.aigateway.lm.agent

/**
 * System prompt for the agent conversation.
 *
 * The prompt no longer uses ReAct free-text format — the gateway drives each step
 * with a targeted JSON prompt instead. The system instruction only needs to establish
 * the agent's identity, its tool awareness, and the fact that it operates in steps.
 */
object AgentSystemPrompt {

    /**
     * Core agent system prompt.
     *
     * Injected as the system instruction for every agent conversation.
     * The tool list is appended to this dynamically by [MessageHandler.buildConversationConfig]
     * based on the toolsets bound to the conversation.
     */
    const val REACT = """You are an intelligent assistant with access to tools.

You work step by step:
1. Read the user's request carefully.
2. Use the available tools to gather the information you need.
3. Observe the results and repeat as necessary until you have enough information to answer.
4. Answer the user's question as best you can using the information you've gathered, then review your answer again.
5. Observe your own answer and improve it if needed, using tools again if necessary.

Always use tools when you need external information — never guess or make up data.
When calling tools, use the exact tool names listed in "Available tools"."""

    /** Compact variant for small context windows. */
    const val REACT_COMPACT = """You are an agent with tools. Use them to answer questions accurately.
Never guess — always use tools for real data. Use exact tool names from the Available tools list."""
}

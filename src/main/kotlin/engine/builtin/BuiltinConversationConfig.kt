package org.thingai.app.aigateway.engine.predefine

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig

object BuiltinConversationConfig {

    /**
     * General-purpose assistant.
     * Balanced sampler — good for everyday Q&A and chat.
     */
    val ASSISTANT: ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(
            "You are a helpful assistant. " +
            "Answer questions clearly and concisely, and ask for clarification when needed."
        ),
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
    )

    /**
     * Software development assistant.
     * Low temperature keeps output deterministic and syntactically correct.
     * Focused on writing, explaining, and reviewing code.
     */
    val CODER: ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(
            "You are an expert software engineer. " +
            "When asked to write code, produce clean, well-commented, production-ready code. " +
            "Prefer concise explanations. Always specify the programming language in code blocks. " +
            "Point out potential bugs or edge cases when relevant."
        ),
        samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.3)
    )

    /**
     * Concise, direct responder.
     * Low temperature and tight sampling for predictable, brief answers.
     * Useful for quick lookups, summaries, and factual queries.
     */
    val CONCISE: ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(
            "You are a concise assistant. " +
            "Always reply in as few words as possible while remaining accurate and complete. " +
            "Avoid filler phrases, preambles, and unnecessary detail. " +
            "Use bullet points or short sentences instead of paragraphs."
        ),
        samplerConfig = SamplerConfig(topK = 10, topP = 0.85, temperature = 0.2)
    )

    /**
     * Creative writing assistant.
     * High temperature and wide sampling for varied, imaginative output.
     * Good for storytelling, brainstorming, and generative tasks.
     */
    val CREATIVE: ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(
            "You are a creative writing assistant with a vivid imagination. " +
            "Generate original, engaging, and expressive content. " +
            "Feel free to use metaphor, narrative, and varied sentence structure. " +
            "When given a prompt, explore it from an unexpected or interesting angle."
        ),
        samplerConfig = SamplerConfig(topK = 80, topP = 0.98, temperature = 1.2)
    )
}

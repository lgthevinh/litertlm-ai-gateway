package org.thingai.app.aigateway.engine.predefine

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig

object BuiltinConversationConfig {
    val ASSISTANT: ConversationConfig = ConversationConfig(
        systemInstruction = Contents.Companion.of("You are a helpful assistant.")
    )
}
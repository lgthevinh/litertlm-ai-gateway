package org.thingai.app.aigateway.engine.define

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig

object PreDefineConversationConfig {
    val ASSISTANT: ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of("You are a helpful assistant.")
    )
}
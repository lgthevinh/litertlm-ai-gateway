package org.thingai.app.aigateway.engine.entity

import com.google.ai.edge.litertlm.Conversation

data class LMConversation(
    val id: String,
    val title: String,
    val conversation: Conversation
)
package org.thingai.app.aigateway.engine.handler

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import org.thingai.app.aigateway.engine.define.PreDefineConversationConfig
import java.util.concurrent.ConcurrentHashMap

class ConversationHandler(
    private val engine: Engine,
) {
    private val conversationList = ConcurrentHashMap<String, Conversation>()

    fun createConversation(name: String, config: ConversationConfig = PreDefineConversationConfig.ASSISTANT): Boolean {
        val created = engine.createConversation(config)
        val existing = conversationList.putIfAbsent(name, created)
        if (existing != null) {
            created.close()
            return false
        }
        return true
    }

    fun sendMessage(conversationName: String, text: String): Message? {
        val conversation = conversationList[conversationName]
        return conversation?.sendMessage(text)
    }

    fun closeConversation(name: String): Boolean {
        val conversation = conversationList.remove(name) ?: return false
        conversation.close()
        return true
    }

    fun getConversationList(): List<String> {
        return conversationList.keys().toList()
    }
}

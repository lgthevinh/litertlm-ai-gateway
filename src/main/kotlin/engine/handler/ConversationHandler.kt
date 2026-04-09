package org.thingai.app.aigateway.engine.handler

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.thingai.app.aigateway.engine.predefine.BuiltinConversationConfig
import java.util.concurrent.ConcurrentHashMap

/**
    * Update soon: Due to each engine can only support one conversation, currently research on session, session handler...
 */
class ConversationHandler(
    private val engine: Engine,
) {
    private val conversationList = ConcurrentHashMap<String, Conversation>()

    /**
     * Creates a new conversation with the given name and config, and adds it to the list.
     * Only one conversation per engine is currently supported
     */
    fun createConversation(name: String, config: ConversationConfig = BuiltinConversationConfig.ASSISTANT): Boolean {
        val created = engine.createConversation(config)
        val existing = conversationList.putIfAbsent(name, created)
        if (existing != null) {
            created.close()
            return false
        }
        return true
    }

    /** Blocking send — used by HTTP REST endpoints. */
    fun sendMessage(conversationName: String, text: String): Message? {
        val conversation = conversationList[conversationName]
        return conversation?.sendMessage(text)
    }

    /**
     * Streaming send — used by WebSocket endpoints.
     *
     * Collects the native LiteRTLM [Conversation.sendMessageAsync] flow and
     * re-emits each partial token as a [WsChunk.Token], followed by
     * [WsChunk.Done] when inference completes, or [WsChunk.Error] on failure.
     *
     * The flow runs on [Dispatchers.IO] so blocking JNI work stays off the
     * main coroutine dispatcher.
     */
    fun sendMessageAsync(conversationName: String, text: String): Flow<WsChunk> = flow {
        val conversation = conversationList[conversationName]
        if (conversation == null) {
            emit(WsChunk.Error("Conversation '$conversationName' not found"))
            return@flow
        }
        conversation.sendMessageAsync(text)
            .catch { e -> emit(WsChunk.Error(e.message ?: "Inference failed")) }
            .collect { message -> emit(WsChunk.Token(message.toString())) }
        emit(WsChunk.Done)
    }.flowOn(Dispatchers.IO)

    fun closeConversation(name: String): Boolean {
        val conversation = conversationList.remove(name) ?: return false
        conversation.close()
        return true
    }

    fun getConversationList(): List<String> {
        return conversationList.keys().toList()
    }
}

/** Frames emitted by [ConversationHandler.sendMessageAsync]. */
sealed class WsChunk {
    /** A partial token streamed from the model during inference. */
    data class Token(val text: String) : WsChunk()
    /** Inference completed — no more tokens for this turn. */
    data object Done : WsChunk()
    /** Inference or routing error. */
    data class Error(val message: String) : WsChunk()
}

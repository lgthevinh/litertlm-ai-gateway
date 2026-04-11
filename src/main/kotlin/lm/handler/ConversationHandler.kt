package org.thingai.app.aigateway.lm.handler

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.thingai.app.aigateway.lm.entity.LMStoredConversation
import org.thingai.app.aigateway.lm.entity.LMStoredMessage
import org.thingai.base.log.ILog

/**
 * Orchestrates conversation lifecycle and message inference.
 *
 * Responsibilities:
 * - Delegate conversation persistence to [MessageHandler]
 * - Delegate inference to [EngineHandler]
 * - Build [ConversationConfig] from stored config + history before each send
 * - Persist user + model messages after each successful reply
 *
 * Does NOT hold any native Conversation objects — all native state is transient
 * inside [EngineHandler.processTask].
 */
class ConversationHandler(
    private val messageHandler: MessageHandler,
    private val engineHandler: EngineHandler,
) {

    companion object {
        private const val TAG = "ConversationHandler"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Creates a new conversation and persists its config to the DB.
     *
     * @param name             Unique conversation name.
     * @param systemInstruction Custom system prompt. Takes priority over [configLabel].
     * @param configLabel      Builtin preset label: "assistant"|"coder"|"concise"|"creative".
     *                         Used when [systemInstruction] is null.
     * @param topK             Sampler top-K.
     * @param topP             Sampler top-P.
     * @param temperature      Sampler temperature.
     * @param tools            Tool names to bind to this conversation (e.g. ["datetime", "calculator"]).
     *                         Empty list means no tools.
     * @return `false` if [name] already exists or the DB insert fails.
     */
    fun createConversation(
        name: String,
        systemInstruction: String? = null,
        configLabel: String = "assistant",
        topK: Int = 40,
        topP: Double = 0.95,
        temperature: Double = 0.8,
        tools: List<String> = emptyList(),
    ): Boolean {
        val record = LMStoredConversation(
            name              = name,
            systemInstruction = systemInstruction?.trim()?.takeIf { it.isNotBlank() },
            configLabel       = configLabel,
            topK              = topK,
            topP              = topP,
            temperature       = temperature,
            tools             = tools.takeIf { it.isNotEmpty() }?.joinToString(","),
            createdAt         = System.currentTimeMillis()
        )
        return messageHandler.saveConversation(record).also { created ->
            if (created) {
                val toolsInfo = if (tools.isNotEmpty()) ", tools=${tools}" else ""
                ILog.i(TAG, "createConversation: '$name' created (config=$configLabel$toolsInfo)")
            }
        }
    }

    /**
     * Updates an existing conversation's mutable fields.
     *
     * All parameters are optional — only non-null values are applied.
     * Pass [clearSystemInstruction] = true to remove a custom system instruction
     * and revert to a builtin preset.
     *
     * @param name                   Current conversation name (lookup key).
     * @param newName                Rename the conversation. All message history is migrated.
     * @param systemInstruction      New custom system prompt.
     * @param clearSystemInstruction Set true to remove the custom system instruction.
     * @param configLabel            Builtin preset: "assistant"|"coder"|"concise"|"creative".
     *                               Only applied when [systemInstruction] is null and
     *                               [clearSystemInstruction] is false.
     * @param topK                   Sampler top-K.
     * @param topP                   Sampler top-P.
     * @param temperature            Sampler temperature.
     * @param tools                  New tool list. Pass empty list to clear all tools.
     * @return `false` if [name] does not exist, [newName] is already taken, or a DB error occurs.
     */
    fun updateConversation(
        name: String,
        newName: String? = null,
        systemInstruction: String? = null,
        clearSystemInstruction: Boolean = false,
        configLabel: String? = null,
        topK: Int? = null,
        topP: Double? = null,
        temperature: Double? = null,
        tools: List<String>? = null,
    ): Boolean {
        return messageHandler.updateConversation(
            name                   = name,
            newName                = newName,
            systemInstruction      = systemInstruction,
            clearSystemInstruction = clearSystemInstruction,
            configLabel            = configLabel,
            topK                   = topK,
            topP                   = topP,
            temperature            = temperature,
            tools                  = tools
        ).also { updated ->
            if (updated) {
                val effectiveName = newName?.trim()?.takeIf { it.isNotBlank() } ?: name
                ILog.i(TAG, "updateConversation: '$name' → '$effectiveName' updated")
            }
        }
    }

    /**
     * Deletes the conversation and all its message history from the DB.
     * @return `true` if the conversation existed and was removed.
     */
    fun deleteConversation(name: String): Boolean =
        messageHandler.deleteConversation(name).also { deleted ->
            if (deleted) ILog.i(TAG, "deleteConversation: '$name' deleted")
        }

    /** Returns `true` if a conversation with [name] exists in the DB. */
    fun hasConversation(name: String): Boolean =
        messageHandler.getConversation(name) != null

    /** Returns all conversation names, newest-first. */
    fun listConversations(): List<String> =
        messageHandler.listConversations()

    /**
     * Returns all stored messages for [name] sorted oldest-first.
     * Returns empty list if the conversation does not exist.
     */
    fun getHistory(name: String): List<LMStoredMessage> =
        messageHandler.getHistory(name)

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Sends [message] to the conversation identified by [name] and streams the reply.
     *
     * Flow:
     * 1. Load stored config from DB — emits [WsChunk.Error] if not found
     * 2. Build [ConversationConfig] with truncated history as initialMessages
     * 3. Submit [ConversationTask] to [EngineHandler] — queues if both engines are busy
     * 4. Emit each [WsChunk.Token] to the caller as tokens arrive
     * 5. On [WsChunk.Done] — persist user + model messages to DB, then emit Done
     * 6. On [WsChunk.Error] — emit Error without persisting (incomplete reply)
     *
     * The flow runs on whatever dispatcher the caller collects on.
     * Native Conversation creation and inference run inside [EngineHandler] on [Dispatchers.Default].
     */
    fun sendMessage(name: String, message: String): Flow<WsChunk> = flow {
        // 1. Load and validate conversation
        val config = messageHandler.buildConfig(name)
        if (config == null) {
            emit(WsChunk.Error("Conversation '$name' not found"))
            return@flow
        }

        // 2. Submit task to engine queue
        val task = ConversationTask(config = config, message = message)
        val replyBuffer = StringBuilder()
        var hasError = false

        engineHandler.submit(task).collect { chunk ->
            when (chunk) {
                is WsChunk.Token -> {
                    replyBuffer.append(chunk.text)
                    emit(chunk)
                }
                is WsChunk.Done -> {
                    // 3. Persist both turns before signalling done
                    val seq = messageHandler.nextSeq(name)
                    messageHandler.appendMessages(
                        conversationName = name,
                        userText         = message,
                        modelText        = replyBuffer.toString(),
                        seq              = seq
                    )
                    emit(WsChunk.Done)
                }
                is WsChunk.Error -> {
                    hasError = true
                    ILog.e(TAG, "sendMessage: inference error for '$name': ${chunk.message}")
                    emit(chunk)
                }
            }
        }

        if (!hasError) {
            ILog.d(TAG, "sendMessage: '$name' turn complete, reply=${replyBuffer.length} chars")
        }
    }
}

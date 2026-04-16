package org.thingai.app.aigateway.lm.handler

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.thingai.app.aigateway.lm.attachment.AttachmentStore
import org.thingai.app.aigateway.lm.conversation.ConversationJobRegistry
import org.thingai.app.aigateway.lm.conversation.ConversationState
import org.thingai.app.aigateway.lm.conversation.ConversationWsChunk
import org.thingai.app.aigateway.lm.attachment.AttachmentRef
import org.thingai.app.aigateway.lm.attachment.AttachmentType
import org.thingai.app.aigateway.lm.entity.LMStoredConversation
import org.thingai.app.aigateway.lm.entity.LMStoredMessage
import org.thingai.base.log.ILog

/**
 * A multimodal attachment received from a client (REST or WS).
 * Raw bytes that have not yet been saved to disk.
 *
 * @param type  Whether this is an image or audio file.
 * @param bytes Raw file content.
 * @param ext   File extension without dot (e.g. "jpg", "png", "wav").
 */
data class IncomingAttachment(
    val type: AttachmentType,
    val bytes: ByteArray,
    val ext: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IncomingAttachment

        if (type != other.type) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (ext != other.ext) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + ext.hashCode()
        return result
    }
}

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
 *
 * Inference is launched as a detached coroutine — it runs to completion regardless
 * of whether the WS client stays connected. [ConversationJobRegistry] tracks job state.
 */
class ConversationHandler(
    private val messageHandler: MessageHandler,
    private val engineHandler: EngineHandler,
    private val attachmentStore: AttachmentStore? = null,
) {

    companion object {
        private const val TAG = "ConversationHandler"
    }

    /** Scope for detached inference coroutines — outlive any individual WS connection. */
    private val inferenceScope = CoroutineScope(Dispatchers.Default)

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
     * @param stateless        When true, no message history is loaded or persisted. Immutable after creation.
     * @return `false` if [name] already exists or the DB insert fails.
     */
    fun createConversation(
        name: String,
        systemInstruction: String? = null,
        configLabel: String = "agent",
        topK: Int = 40,
        topP: Double = 0.95,
        temperature: Double = 0.8,
        tools: List<String> = emptyList(),
        stateless: Boolean = false,
        thinkingEnabled: Boolean = true,
    ): Boolean {
        val record = LMStoredConversation(
            name              = name,
            systemInstruction = systemInstruction?.trim()?.takeIf { it.isNotBlank() },
            configLabel       = configLabel,
            topK              = topK,
            topP              = topP,
            temperature       = temperature,
            tools             = tools.takeIf { it.isNotEmpty() }?.joinToString(","),
            createdAt         = System.currentTimeMillis(),
            stateless         = stateless,
            agentMode         = configLabel.trim().lowercase() == "agent",
            thinkingEnabled   = thinkingEnabled
        )
        return messageHandler.saveConversation(record).also { created ->
            if (created) {
                val toolsInfo    = if (tools.isNotEmpty()) ", tools=$tools" else ""
                val thinkingInfo = if (!thinkingEnabled) ", thinking=off" else ""
                ILog.i(TAG, "createConversation: '$name' created (config=$configLabel$toolsInfo$thinkingInfo)")
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
     * @param name                   Conversation name (lookup key).
     * @param systemInstruction      New custom system prompt.
     * @param clearSystemInstruction Set true to remove the custom system instruction.
     * @param configLabel            Builtin preset: "assistant"|"coder"|"concise"|"creative".
     *                               Only applied when [systemInstruction] is null and
     *                               [clearSystemInstruction] is false.
     * @param topK                   Sampler top-K.
     * @param topP                   Sampler top-P.
     * @param temperature            Sampler temperature.
     * @param tools                  New tool list. Pass empty list to clear all tools.
     * @return `false` if [name] does not exist or a DB error occurs.
     */
    fun updateConversation(
        name: String,
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
            systemInstruction      = systemInstruction,
            clearSystemInstruction = clearSystemInstruction,
            configLabel            = configLabel,
            topK                   = topK,
            topP                   = topP,
            temperature            = temperature,
            tools                  = tools
        ).also { updated ->
            if (updated) ILog.i(TAG, "updateConversation: '$name' updated")
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

    /** Returns all conversations as (name, stateless, agentMode) triples, newest-first. */
    fun listConversations(): List<Triple<String, Boolean, Boolean>> =
        messageHandler.listConversations()

    /**
     * Returns all stored messages for [name] sorted oldest-first.
     * Returns empty list if the conversation does not exist.
     */
    fun getHistory(name: String): List<LMStoredMessage> =
        messageHandler.getHistory(name)

    /** Returns `true` if thinking is currently enabled for this conversation. */
    fun isThinkingEnabled(name: String): Boolean = messageHandler.isThinkingEnabled(name)

    /**
     * Permanently toggles thinking for [name].
     * @return false if the conversation does not exist.
     */
    fun setThinkingEnabled(name: String, enabled: Boolean): Boolean =
        messageHandler.setThinkingEnabled(name, enabled)

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Returns the current [ConversationState] for [name].
     */
    fun getConversationState(name: String): ConversationState =
        ConversationJobRegistry.getState(name)

    /** Returns the current number of tasks queued + in progress on the engine. */
    fun getQueueSize(): Int = engineHandler.getQueueSize()

    /** Returns an ordered snapshot of the queue with conversation names, positions, and statuses. */
    fun getQueueEntries(): List<QueueEntry> = engineHandler.getQueueEntries()

    /**
     * Submits [message] (with optional multimodal [attachments]) to the conversation
     * identified by [name].
     *
     * Inference is launched in a **detached coroutine** ([inferenceScope]) that runs to
     * completion regardless of whether the caller's flow is still being collected.
     *
     * Flow behaviour:
     * - If conversation not found → emits [ConversationWsChunk.Error]
     * - If conversation is BUSY  → emits [ConversationWsChunk.Error] ("conversation is busy")
     * - Otherwise                → starts a BUSY job, launches inference, emits [ConversationWsChunk.Busy]
     *                              so the WS handler knows to await [ConversationJobRegistry]
     *
     * Persistence: user + model messages are written to DB inside the detached coroutine
     * on [ConversationWsChunk.Done], before the job transitions to DONE.
     */
    fun sendMessage(
        name: String,
        message: String,
        attachments: List<IncomingAttachment> = emptyList(),
        thinkingOverride: Boolean? = null
    ): Flow<ConversationWsChunk> = flow {
        // 1. Guard — conversation must exist
        val config = messageHandler.buildConfig(name, thinkingOverride)
        if (config == null) {
            emit(ConversationWsChunk.Error("Conversation '$name' not found"))
            return@flow
        }

        val agentMode = messageHandler.isAgentMode(name)
        ILog.i(TAG, "sendMessage: '$name' agentMode=$agentMode thinking=${thinkingOverride ?: messageHandler.isThinkingEnabled(name)}")

        // 2. Guard — reject if already BUSY
        if (ConversationJobRegistry.isBusy(name)) {
            emit(ConversationWsChunk.Error("Conversation '$name' is busy — please wait for the current reply to complete"))
            return@flow
        }

        // 3. Register job — IDLE → BUSY
        val job = ConversationJobRegistry.startJob(name)

        // 4. Build multimodal Contents + save attachments to disk
        val contentParts = mutableListOf<Content>(Content.Text(message))
        val attachmentRefs = mutableListOf<AttachmentRef>()

        if (attachments.isNotEmpty() && attachmentStore != null) {
            val seq = messageHandler.nextSeq(name)
            attachments.forEachIndexed { index, att ->
                // For initial send: use in-memory bytes (ImageBytes / AudioBytes)
                when (att.type) {
                    AttachmentType.IMAGE -> contentParts += Content.ImageBytes(att.bytes)
                    AttachmentType.AUDIO -> contentParts += Content.AudioBytes(att.bytes)
                }
                // Save to disk for future history replay
                val filename = attachmentStore.save(name, seq, index, att.ext, att.bytes)
                attachmentRefs += AttachmentRef(att.type, filename)
            }
            ILog.d(TAG, "sendMessage: '$name' built Contents with ${attachments.size} attachment(s)")
        }

        val contents = Contents.of(contentParts)

        // 5. Launch detached inference — survives WS disconnect
        inferenceScope.launch {
            val task = ConversationTask(
                convName       = name,
                config         = config,
                contents       = contents,
                userText       = message,
                attachmentRefs = attachmentRefs,
                agentMode      = messageHandler.isAgentMode(name)
            )

            engineHandler.submit(task).collect { chunk ->
                when (chunk) {
                    is ConversationWsChunk.Token -> {
                        ConversationJobRegistry.onToken(name, chunk.text)
                    }
                    is ConversationWsChunk.Done -> {
                        // Persist only for stateful conversations
                        if (!messageHandler.isStateless(name)) {
                            val seq = messageHandler.nextSeq(name)
                            messageHandler.appendMessages(
                                conversationName = name,
                                userText         = task.userText,
                                modelText        = job.replyBuffer.toString(),
                                seq              = seq,
                                attachments      = task.attachmentRefs
                            )
                        }
                        ConversationJobRegistry.onDone(name)
                        ILog.d(TAG, "sendMessage: '$name' complete, reply=${job.replyBuffer.length} chars")
                    }
                    is ConversationWsChunk.Error -> {
                        ILog.e(TAG, "sendMessage: inference error for '$name': ${chunk.message}")
                        ConversationJobRegistry.onError(name, chunk.message)
                    }
                    // Agent step chunks — forward to agentFlow so WS clients can observe them
                    is ConversationWsChunk.AgentThinking,
                    is ConversationWsChunk.AgentToolCall,
                    is ConversationWsChunk.AgentToolResult,
                    is ConversationWsChunk.AgentMaxSteps -> {
                        ConversationJobRegistry.getBusyJob(name)?.agentFlow?.tryEmit(chunk)
                    }
                    else -> Unit
                }
            }
        }

        // 6. Signal WS handler — queued position if engine is busy, then Busy
        val queueSize = engineHandler.getQueueSize()
        if (queueSize > 0) {
            emit(ConversationWsChunk.Queued(position = queueSize + 1))
        }
        emit(ConversationWsChunk.Busy)
    }
}

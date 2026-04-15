package org.thingai.app.aigateway.lm.handler

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import org.thingai.app.aigateway.lm.attachment.AttachmentStore
import org.thingai.app.aigateway.lm.attachment.AttachmentRef
import org.thingai.app.aigateway.lm.entity.LMStoredConversation
import org.thingai.app.aigateway.lm.entity.LMStoredMessage
import org.thingai.app.aigateway.lm.attachment.toColumnValue
import org.thingai.app.aigateway.lm.builtin.BuiltinConversationConfig
import org.thingai.app.aigateway.lm.tool.ToolRegistry
import org.thingai.base.dao.exceptions.DaoException
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite
import java.util.UUID

/**
 * Handles all DB persistence for conversations and their message history.
 *
 * Responsibilities:
 * - Store and retrieve [LMStoredConversation] config records
 * - Store and retrieve [LMStoredMessage] history rows
 * - Rebuild a [ConversationConfig] with truncated [initialMessages] for engine re-open
 *
 * No engine or inference knowledge lives here.
 */
class MessageHandler(
    private val dao: DaoSqlite,
    private val attachmentStore: AttachmentStore? = null
) {

    companion object {
        private const val TAG = "MessageHandler"

        /** Maximum number of messages (user + model combined) passed as initialMessages on re-open. */
        const val HISTORY_LIMIT = 20
    }

    // ── Conversation lifecycle ────────────────────────────────────────────────

    /**
     * Persists a new conversation config record.
     * @return `false` if [name] already exists in the DB or on insert failure.
     */
    fun saveConversation(record: LMStoredConversation): Boolean {
        val existing = getConversation(record.name)
        if (existing != null) {
            ILog.d(TAG, "saveConversation: '${record.name}' already exists")
            return false
        }
        return try {
            dao.insertOrUpdate(record)
            ILog.i(TAG, "saveConversation: '${record.name}' saved")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "saveConversation: DB error: ${e.message}")
            false
        }
    }

    /**
     * Loads the stored config record for [name], or `null` if not found.
     */
    fun getConversation(name: String): LMStoredConversation? {
        return try {
            dao.query(LMStoredConversation::class.java, "name", name).firstOrNull()
        } catch (e: DaoException) {
            ILog.e(TAG, "getConversation: DB error: ${e.message}")
            null
        }
    }

    /**
     * Returns all stored conversations as (name, stateless) pairs, sorted newest-first.
     */
    fun listConversations(): List<Pair<String, Boolean>> {
        return try {
            dao.readAll(LMStoredConversation::class.java)
                .sortedByDescending { it.createdAt }
                .map { it.name to it.stateless }
        } catch (e: DaoException) {
            ILog.e(TAG, "listConversations: DB error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Updates an existing conversation record with the provided fields.
     *
     * Only non-null parameters overwrite the stored value — null means "leave unchanged".
     * [configLabel] is automatically set to "custom" when [systemInstruction] is provided.
     *
     * @return `false` if the conversation does not exist or a DB error occurs.
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
        val record = getConversation(name) ?: run {
            ILog.d(TAG, "updateConversation: '$name' not found")
            return false
        }

        // Resolve updated fields
        val resolvedInstruction = when {
            clearSystemInstruction -> null
            systemInstruction != null -> systemInstruction.trim().takeIf { it.isNotBlank() }
            else -> record.systemInstruction
        }
        val resolvedConfigLabel = when {
            resolvedInstruction != null && systemInstruction != null -> "custom"
            clearSystemInstruction && configLabel != null -> configLabel
            clearSystemInstruction -> "assistant"
            configLabel != null -> configLabel
            else -> record.configLabel
        }
        val resolvedTools = when {
            tools != null -> tools.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(",")
            else -> record.tools
        }

        return try {
            val updated = record.copy(
                systemInstruction = resolvedInstruction,
                configLabel       = resolvedConfigLabel,
                topK              = topK ?: record.topK,
                topP              = topP ?: record.topP,
                temperature       = temperature ?: record.temperature,
                tools             = resolvedTools
            )
            dao.insertOrUpdate(updated)
            ILog.i(TAG, "updateConversation: '$name' updated")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "updateConversation: DB error: ${e.message}")
            false
        }
    }

    /**
     * Deletes the conversation config, all message history, and attachment files from disk.
     * @return `true` if the conversation existed and was deleted.
     */
    fun deleteConversation(name: String): Boolean {
        val record = getConversation(name) ?: run {
            ILog.d(TAG, "deleteConversation: '$name' not found")
            return false
        }
        return try {
            // Delete all messages for this conversation
            val messages = dao.query(LMStoredMessage::class.java, "conversationName", name)
            messages.forEach { dao.delete(it) }

            dao.delete(record)

            // Clean up attachment files on disk
            attachmentStore?.deleteConversation(name)

            ILog.i(TAG, "deleteConversation: '$name' deleted, ${messages.size} message(s) removed")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "deleteConversation: DB error: ${e.message}")
            false
        }
    }

    // ── Message history ───────────────────────────────────────────────────────

    /**
     * Appends a user message and the model reply as a pair to the DB.
     * Both share the same [seq] base — user is [seq], model is [seq] + 1.
     *
     * @param attachments Optional attachment references for the user message (multimodal).
     *
     * Call [nextSeq] first to get the correct base sequence number.
     */
    fun appendMessages(
        conversationName: String,
        userText: String,
        modelText: String,
        seq: Int,
        attachments: List<AttachmentRef> = emptyList()
    ) {
        val now = System.currentTimeMillis()
        try {
            dao.insertOrUpdate(
                LMStoredMessage(
                    id               = UUID.randomUUID().toString(),
                    conversationName = conversationName,
                    role             = "user",
                    text             = userText,
                    attachments      = attachments.toColumnValue(),
                    seq              = seq,
                    createdAt        = now
                )
            )
            dao.insertOrUpdate(
                LMStoredMessage(
                    id               = UUID.randomUUID().toString(),
                    conversationName = conversationName,
                    role             = "model",
                    text             = modelText,
                    seq              = seq + 1,
                    createdAt        = now
                )
            )
            val attInfo = if (attachments.isNotEmpty()) " (${attachments.size} attachment(s))" else ""
            ILog.d(TAG, "appendMessages: 2 messages saved for '$conversationName' at seq=$seq$attInfo")
        } catch (e: DaoException) {
            ILog.e(TAG, "appendMessages: DB error: ${e.message}")
        }
    }

    /**
     * Returns the next available sequence number for [conversationName].
     * Returns 0 if no messages exist yet.
     */
    fun nextSeq(conversationName: String): Int {
        return try {
            val messages = dao.query(LMStoredMessage::class.java, "conversationName", conversationName)
            if (messages.isEmpty()) 0 else messages.maxOf { it.seq } + 1
        } catch (e: DaoException) {
            ILog.e(TAG, "nextSeq: DB error: ${e.message}")
            0
        }
    }

    // ── Config reconstruction ─────────────────────────────────────────────────

    /**
     * Returns all stored messages for [conversationName] sorted by [seq] ascending,
     * as raw [LMStoredMessage] records for API responses.
     */
    fun getHistory(conversationName: String): List<LMStoredMessage> {
        return try {
            dao.query(LMStoredMessage::class.java, "conversationName", conversationName)
                .sortedBy { it.seq }
        } catch (e: DaoException) {
            ILog.e(TAG, "getHistory: DB error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Loads the last [HISTORY_LIMIT] messages for [conversationName] ordered by [seq],
     * maps them to [Message] objects, and returns a [ConversationConfig] ready for
     * engine re-open.
     *
     * The [LMStoredConversation.systemInstruction] is applied if present; otherwise
     * the builtin preset identified by [LMStoredConversation.configLabel] is used as
     * the base and its sampler is overridden with the stored values.
     */
    fun buildConfig(conversationName: String): ConversationConfig? {
        val record = getConversation(conversationName) ?: return null
        // Stateless conversations always start with empty history
        val history = if (record.stateless) emptyList() else loadHistory(conversationName)
        return buildConversationConfig(record, history)
    }

    /** Returns true if the conversation is stateless (no history load or persistence). */
    fun isStateless(conversationName: String): Boolean =
        getConversation(conversationName)?.stateless ?: false

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Loads the last [HISTORY_LIMIT] messages for [conversationName], sorted by [seq] ascending.
     * Only the text portion of each message is included — attachments are not replayed into
     * the engine context.
     */
    private fun loadHistory(conversationName: String): List<Message> {
        return try {
            val all = dao.query(LMStoredMessage::class.java, "conversationName", conversationName)
            all.sortedBy { it.seq }
                .takeLast(HISTORY_LIMIT)
                .map { it.toMessage(conversationName, attachmentStore) }
        } catch (e: DaoException) {
            ILog.e(TAG, "loadHistory: DB error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Builds a [ConversationConfig] from a [LMStoredConversation] record and a history list.
     *
     * - If [LMStoredConversation.systemInstruction] is set -> use it directly
     * - Otherwise -> derive system instruction from the builtin preset label
     * - Sampler always uses the stored [topK]/[topP]/[temperature] values
     * - If [LMStoredConversation.tools] is non-blank, resolve tool names via [ToolRegistry]
     *   using the SDK `tool()` function and enable [automaticToolCalling].
     */
    private fun buildConversationConfig(
        record: LMStoredConversation,
        history: List<Message>
    ): ConversationConfig {
        val systemInstruction = record.systemInstruction?.takeIf { it.isNotBlank() }
            ?: builtinInstruction(record.configLabel)

        // Resolve tool names → List<ToolProvider> via ToolRegistry + SDK tool()
        val toolNames = record.tools
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val toolProviders = if (toolNames.isNotEmpty()) ToolRegistry.getToolProviders(toolNames) else emptyList()
        val autoToolCalling = toolProviders.isNotEmpty()

        if (toolNames.isNotEmpty()) {
            ILog.d(TAG, "buildConversationConfig: tools=$toolNames resolved=${toolProviders.size} autoToolCalling=$autoToolCalling")
        }

        return ConversationConfig(
            systemInstruction    = Contents.of(systemInstruction),
            initialMessages      = history,
            samplerConfig        = SamplerConfig(
                topK        = record.topK,
                topP        = record.topP,
                temperature = record.temperature
            ),
            tools                = toolProviders,
            automaticToolCalling = autoToolCalling
        )
    }

    /**
     * Extracts the system instruction text from a builtin preset by label.
     * Falls back to ASSISTANT if the label is unrecognised.
     */
    private fun builtinInstruction(label: String): String {
        val preset = when (label.trim().lowercase()) {
            "coder"    -> BuiltinConversationConfig.CODER
            "concise"  -> BuiltinConversationConfig.CONCISE
            "creative" -> BuiltinConversationConfig.CREATIVE
            else       -> BuiltinConversationConfig.ASSISTANT
        }
        // Extract the text from the preset's Contents — toString() returns the plain text
        return preset.systemInstruction?.toString() ?: ""
    }
}

// ── Extensions ────────────────────────────────────────────────────────────────

/**
 * Maps a [LMStoredMessage] row to a LiteRTLM [Message] for use in [ConversationConfig.initialMessages].
 *
 * Attachments (images / audio) are intentionally **not** re-injected into history — the
 * engine only receives the text portion of past turns.  This avoids loading large binary
 * files on every conversation re-open and sidesteps model instability with multimodal
 * history replay.  The attachment files remain on disk and are still served via the API.
 */
private fun LMStoredMessage.toMessage(
    conversationName: String,
    attachmentStore: AttachmentStore?
): Message = when (role) {
    "model" -> Message.model(text)
    else    -> Message.user(text)
}

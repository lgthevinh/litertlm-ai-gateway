package org.thingai.app.aigateway.lm.handler

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.thingai.app.aigateway.lm.conversation.ConversationWsChunk
import org.thingai.app.aigateway.lm.attachment.AttachmentRef
import org.thingai.base.log.ILog
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

/**
 * A single inference task submitted to the [EngineHandler] queue.
 *
 * @param convName       The conversation this task belongs to (used for queue visibility).
 * @param config         The fully-built [ConversationConfig] including system instruction,
 *                       sampler config, history, and tools.
 * @param contents       The multimodal user message to send (text, images, audio wrapped in [Contents]).
 * @param userText       The plain-text portion of the user message (for DB persistence).
 * @param attachmentRefs Attachment references for this message (for DB persistence). Empty for text-only.
 * @param reply          Channel used to stream [ConversationWsChunk] frames back to the caller.
 *                       The worker sends tokens, then [ConversationWsChunk.Done] or
 *                       [ConversationWsChunk.Error], then closes the channel.
 */
data class ConversationTask(
    val convName: String,
    val config: ConversationConfig,
    val contents: Contents,
    val userText: String = "",
    val attachmentRefs: List<AttachmentRef> = emptyList(),
    val reply: Channel<ConversationWsChunk> = Channel(Channel.UNLIMITED)
)

/**
 * Represents an entry in the inference queue, returned by [EngineHandler.getQueueEntries].
 *
 * @param name     Conversation name.
 * @param position 1-based position (1 = currently processing).
 * @param status   "processing" for position 1, "waiting" for others.
 */
data class QueueEntry(
    val name: String,
    val position: Int,
    val status: String
)

/**
 * Owns a single [Engine] instance and processes inference tasks one at a time.
 *
 * Architecture:
 * - One dedicated thread ([engineThread]) owns the engine for its entire lifetime.
 *   Both [Engine.initialize] and all [processTask] calls run on this thread to satisfy
 *   LiteRTLM native thread-affinity requirements.
 * - An unbounded [taskChannel] accepts [ConversationTask] submissions from any caller.
 *   Tasks are serialized — one at a time — naturally via the channel.
 * - A [queueNames] deque tracks conversation names in submission order for queue visibility.
 *
 * Lifecycle:
 * - Call [start] once after construction to initialize the engine and launch the worker.
 * - Call [stop] to shut down gracefully — closes the task channel and the engine.
 */
class EngineHandler(private val engineConfig: EngineConfig) {

    companion object {
        private const val TAG = "EngineHandler"
    }

    /** Unbounded queue — callers enqueue here and wait their turn. */
    private val taskChannel = Channel<ConversationTask>(Channel.UNLIMITED)

    /**
     * Ordered list of conversation names currently in the queue (including the one being processed).
     * First element = currently processing. Thread-safe.
     */
    private val queueNames = ConcurrentLinkedDeque<String>()

    /** Single dedicated thread — engine init and all inference run here. */
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "engine-thread").also { it.isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(engineThread)

    private var engine: Engine? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initializes the engine on [engineThread] then launches the worker loop on the same thread.
     *
     * @param onReady Called with `true` when ready, `false` if initialization fails.
     */
    fun start(onReady: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                engine = Engine(engineConfig).also {
                    it.initialize()
                    ILog.i(TAG, "start: engine ready")
                }
                launchWorker()
                onReady(true)
            } catch (e: Exception) {
                ILog.e(TAG, "start: engine init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    /**
     * Closes the task channel and the engine.
     */
    fun stop() {
        taskChannel.close()
        runCatching { engine?.close() }
        runCatching { (engineThread as? java.io.Closeable)?.close() }
        ILog.i(TAG, "stop: engine closed")
    }

    // ── Submission ────────────────────────────────────────────────────────────

    /**
     * Submits a [ConversationTask] to the queue and returns a [Flow] of [ConversationWsChunk].
     *
     * The flow suspends until the worker picks up the task, then emits tokens as they arrive.
     * Completes when [ConversationWsChunk.Done] or [ConversationWsChunk.Error] is received.
     */
    fun submit(task: ConversationTask): Flow<ConversationWsChunk> = flow {
        queueNames.addLast(task.convName)
        taskChannel.send(task)
        ILog.d(TAG, "submit: '${task.convName}' queued (queue: ${queueNames.size})")

        for (chunk in task.reply) {
            emit(chunk)
            if (chunk is ConversationWsChunk.Done || chunk is ConversationWsChunk.Error) break
        }
    }

    /** Returns the current number of tasks queued + in progress. */
    fun getQueueSize(): Int = queueNames.size

    /**
     * Returns an ordered snapshot of the queue with position and status for each entry.
     * Position 1 = currently processing, position 2+ = waiting.
     */
    fun getQueueEntries(): List<QueueEntry> {
        return queueNames.toList().mapIndexed { index, name ->
            QueueEntry(
                name     = name,
                position = index + 1,
                status   = if (index == 0) "processing" else "waiting"
            )
        }
    }

    // ── Worker ────────────────────────────────────────────────────────────────

    /**
     * Loops on [taskChannel] on [engineThread], processing one task at a time.
     * Called from [start] — already running on [engineThread].
     */
    private fun launchWorker() {
        scope.launch {
            ILog.d(TAG, "worker: started")

            for (task in taskChannel) {
                ILog.d(TAG, "worker: picked up '${task.convName}'")
                engine?.let { processTask(it, task) }
                    ?: task.reply.send(ConversationWsChunk.Error("Engine not initialized"))
                queueNames.pollFirst()
            }

            ILog.d(TAG, "worker: channel closed, shutting down")
        }
    }

    /**
     * Runs a single [ConversationTask]:
     * 1. Creates a transient [Conversation] from [task.config]
     * 2. Streams tokens into [task.reply]
     * 3. Sends [ConversationWsChunk.Done] on completion or [ConversationWsChunk.Error] on failure
     * 4. Closes the [Conversation] to release engine resources
     *
     * Always runs on [engineThread] — same thread the engine was initialized on.
     */
    private suspend fun processTask(engine: Engine, task: ConversationTask) {
        var conversation: Conversation? = null
        var inferenceError = false
        try {
            conversation = engine.createConversation(task.config)

            conversation.sendMessageAsync(task.contents)
                .catch { e ->
                    ILog.e(TAG, "worker: inference error: ${e.message}")
                    task.reply.send(ConversationWsChunk.Error(e.message ?: "Inference failed"))
                    inferenceError = true
                }
                .collect { message ->
                    task.reply.send(ConversationWsChunk.Token(message.toString()))
                }

            if (!inferenceError) {
                task.reply.send(ConversationWsChunk.Done)
                ILog.d(TAG, "worker: '${task.convName}' complete")
            }
        } catch (e: Exception) {
            ILog.e(TAG, "worker: '${task.convName}' failed: ${e.message}")
            runCatching { task.reply.send(ConversationWsChunk.Error(e.message ?: "Unknown error")) }
        } finally {
            runCatching { conversation?.close() }
            task.reply.close()
        }
    }
}

package org.thingai.app.aigateway.lm.handler

import com.google.ai.edge.litertlm.Conversation
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
import kotlinx.coroutines.withContext
import org.thingai.app.aigateway.lm.conversation.ConversationWsChunk
import org.thingai.base.log.ILog
import java.util.concurrent.Executors

/**
 * A single inference task submitted to the [EngineHandler] queue.
 *
 * @param config   The fully-built [com.google.ai.edge.litertlm.ConversationConfig]
 *                 including system instruction, sampler, and initial messages (history).
 * @param message  The new user message to send.
 * @param reply    Channel used to stream [ConversationWsChunk] frames back to the caller.
 *                 The worker sends tokens, then [ConversationWsChunk.Done] or [ConversationWsChunk.Error],
 *                 then closes the channel.
 */
data class ConversationTask(
    val config: ConversationConfig,
    val message: String,
    val reply: Channel<ConversationWsChunk> = Channel(Channel.UNLIMITED)
)

/**
 * Manages a fixed pool of [Engine] instances and a shared task queue.
 *
 * Architecture:
 * - [ENGINE_COUNT] engines are initialised on [start].
 * - A single unbounded [taskChannel] accepts [ConversationTask] submissions from any caller.
 * - One coroutine worker per engine loops on the queue — picks a task, runs inference,
 *   streams [ConversationWsChunk] frames back through [ConversationTask.reply], then picks the next task.
 * - No engine is shared between workers — each worker owns its engine exclusively,
 *   so no locking is needed during inference.
 * - If all engines are busy, callers queue and wait naturally (backpressure via Channel).
 *
 * Lifecycle:
 * - Call [start] once after construction to initialise engines and launch workers.
 * - Call [stop] to shut down gracefully (closes the task channel, joins workers).
 */
class EngineHandler(private val engineConfig: EngineConfig) {

    companion object {
        private const val TAG = "EngineHandler"
        const val ENGINE_COUNT = 2
    }

    /** Shared unbounded queue — all callers enqueue here regardless of which engine picks it up. */
    private val taskChannel = Channel<ConversationTask>(Channel.UNLIMITED)

    /**
     * Each engine is paired with a single-thread dispatcher.
     * The engine is initialized on that thread and all inference runs on the same thread —
     * required for LiteRTLM native thread affinity.
     */
    private data class EngineEntry(
        val engine: Engine,
        val dispatcher: kotlinx.coroutines.CoroutineDispatcher
    )

    private val engineEntries = mutableListOf<EngineEntry>()
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialises [ENGINE_COUNT] engines, each on its own dedicated single thread,
     * then launches one worker coroutine per engine pinned to that same thread.
     *
     * Each engine is initialized and used exclusively on its dedicated thread to satisfy
     * LiteRTLM native thread-affinity requirements.
     *
     * @param onReady Called once all engines are ready. Called with `false` if any engine fails.
     */
    fun start(onReady: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                repeat(ENGINE_COUNT) { index ->
                    // Dedicated single thread for this engine — init and inference always run here
                    val dispatcher = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "engine-thread-$index").also { it.isDaemon = true }
                    }.asCoroutineDispatcher()

                    val engine = withContext(dispatcher) {
                        Engine(engineConfig).also { it.initialize() }
                    }

                    engineEntries.add(EngineEntry(engine, dispatcher))
                    ILog.i(TAG, "start: engine #$index ready")
                }

                // Launch one worker per engine, pinned to its dedicated thread
                engineEntries.forEachIndexed { index, entry ->
                    launchWorker(index, entry)
                }

                ILog.i(TAG, "start: all $ENGINE_COUNT engines ready")
                onReady(true)
            } catch (e: Exception) {
                ILog.e(TAG, "start: engine init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    /**
     * Closes the task channel and all engines.
     * In-flight tasks will complete; queued tasks will receive an error.
     */
    fun stop() {
        taskChannel.close()
        engineEntries.forEach { entry ->
            runCatching { entry.engine.close() }
            runCatching { (entry.dispatcher as? java.io.Closeable)?.close() }
        }
        ILog.i(TAG, "stop: all engines closed")
    }

    // ── Submission ────────────────────────────────────────────────────────────

    /**
     * Submits a [ConversationTask] to the queue and returns a [Flow] of [ConversationWsChunk] frames.
     *
     * The flow suspends until a worker picks up the task, then emits tokens as they arrive.
     * Completes when [ConversationWsChunk.Done] is received or an error occurs.
     *
     * Callers collect this flow to receive the streaming reply — both REST (collect all,
     * return final string) and WebSocket (emit each frame) routes use this same path.
     */
    fun submit(task: ConversationTask): Flow<ConversationWsChunk> = flow {
        taskChannel.send(task)
        ILog.d(TAG, "submit: task queued")

        for (chunk in task.reply) {
            emit(chunk)
            if (chunk is ConversationWsChunk.Done || chunk is ConversationWsChunk.Error) break
        }
    }

    // ── Worker ────────────────────────────────────────────────────────────────

    /**
     * Launches a worker coroutine pinned to [entry.dispatcher] (a single dedicated thread).
     * The worker loops on [taskChannel], processing one task at a time on that thread.
     * All engine calls (createConversation, sendMessageAsync, close) run on the same thread
     * the engine was initialized on.
     */
    private fun launchWorker(index: Int, entry: EngineEntry) {
        scope.launch(entry.dispatcher) {
            ILog.d(TAG, "worker #$index: started")

            for (task in taskChannel) {
                ILog.d(TAG, "worker #$index: picked up task")
                processTask(index, entry.engine, task)
            }

            ILog.d(TAG, "worker #$index: channel closed, shutting down")
        }
    }

    /**
     * Runs a single [ConversationTask] on [engine].
     * Already running on the engine's dedicated thread via [launchWorker].
     * 1. Creates a native Conversation with [ConversationTask.config]
     * 2. Streams tokens from [sendMessageAsync] into [ConversationTask.reply]
     * 3. Sends [ConversationWsChunk.Done] on completion or [ConversationWsChunk.Error] on failure
     * 4. Closes the native Conversation to release engine resources
     */
    private suspend fun processTask(index: Int, engine: Engine, task: ConversationTask) {
        var conversation: Conversation? = null
        var inferenceError = false
        try {
            conversation = engine.createConversation(task.config)

            conversation.sendMessageAsync(task.message)
                .catch { e ->
                    ILog.e(TAG, "worker #$index: inference error: ${e.message}")
                    task.reply.send(ConversationWsChunk.Error(e.message ?: "Inference failed"))
                    inferenceError = true
                }
                .collect { message ->
                    task.reply.send(ConversationWsChunk.Token(message.toString()))
                }

            if (!inferenceError) {
                task.reply.send(ConversationWsChunk.Done)
                ILog.d(TAG, "worker #$index: task complete")
            }
        } catch (e: Exception) {
            ILog.e(TAG, "worker #$index: task failed: ${e.message}")
            runCatching { task.reply.send(ConversationWsChunk.Error(e.message ?: "Unknown error")) }
        } finally {
            runCatching { conversation?.close() }
            task.reply.close()
        }
    }
}

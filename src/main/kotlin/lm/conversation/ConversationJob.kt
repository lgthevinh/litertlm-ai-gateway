package org.thingai.app.aigateway.lm.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Represents the lifecycle state of an in-flight inference turn.
 *
 * Transitions:
 *   IDLE  — not in registry (default)
 *   BUSY  — inference is running; [completionDeferred] is not yet resolved
 *
 * There is no DONE state — the job is removed from the registry immediately when
 * inference completes (inside [ConversationJobRegistry.onDone]).
 */
enum class ConversationState { IDLE, BUSY }

/**
 * Holds all runtime state for one active inference turn on a conversation.
 *
 * Created by [ConversationJobRegistry.startJob] when a message is submitted.
 * Cleared immediately by [ConversationJobRegistry.onDone] when inference completes,
 * or by [ConversationJobRegistry.onError] on failure / watchdog timeout.
 *
 * @param convName           The conversation this job belongs to.
 * @param replyBuffer        Accumulates token text as inference progresses.
 * @param completionDeferred Resolved with the full reply string when inference completes,
 *                           or completed exceptionally on error. WS/REST handlers await this.
 * @param tokenFlow          Hot shared flow that emits each token as it arrives from inference.
 *                           WS handlers collect this in a child coroutine to stream tokens to
 *                           the client without blocking the incoming frame loop.
 *                           Uses DROP_OLDEST on overflow — backpressure from slow clients
 *                           drops old tokens rather than blocking inference.
 * @param watchdogJob        Coroutine job that fires after [ConversationJobRegistry.WATCHDOG_MS]
 *                           of silence (no token received). Reset on each token.
 */
data class ConversationJob(
    val convName: String,
    val replyBuffer: StringBuilder = StringBuilder(),
    val completionDeferred: CompletableDeferred<String> = CompletableDeferred(),
    val tokenFlow: MutableSharedFlow<String> = MutableSharedFlow(
        extraBufferCapacity = 256,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    ),
    @Volatile var watchdogJob: Job? = null,
    @Volatile var state: ConversationState = ConversationState.BUSY,
)

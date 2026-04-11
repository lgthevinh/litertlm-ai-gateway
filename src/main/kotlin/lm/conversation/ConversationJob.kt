package org.thingai.app.aigateway.lm.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * Represents the lifecycle state of an in-flight or completed inference turn.
 *
 * Transitions:
 *   IDLE  — no active job (default, not stored in registry)
 *   BUSY  — inference is running; [completionDeferred] is not yet resolved
 *   DONE  — inference completed; reply is persisted to DB and buffered in [reply];
 *           eviction timer is running
 */
enum class ConversationState { IDLE, BUSY, DONE }

/**
 * Holds all runtime state for one active inference turn on a conversation.
 *
 * Created by [ConversationJobRegistry.startJob] when a message is submitted.
 * Cleared by [ConversationJobRegistry.consume] when a WS client reads the result,
 * or by the eviction timer after [ConversationJobRegistry.EVICTION_TTL_MS] of inactivity
 * post-completion.
 *
 * @param convName           The conversation this job belongs to.
 * @param replyBuffer        Accumulates token text as inference progresses.
 * @param completionDeferred Resolved with the full reply string when inference completes,
 *                           or completed exceptionally on error. WS handlers await this.
 * @param watchdogJob        Coroutine job that fires after [ConversationJobRegistry.WATCHDOG_MS]
 *                           of silence (no token received). Reset on each token.
 * @param evictionJob        Coroutine job that clears the registry entry after
 *                           [ConversationJobRegistry.EVICTION_TTL_MS] following DONE.
 *                           Started when state transitions to DONE.
 */
data class ConversationJob(
    val convName: String,
    val replyBuffer: StringBuilder = StringBuilder(),
    val completionDeferred: CompletableDeferred<String> = CompletableDeferred(),
    @Volatile var watchdogJob: Job? = null,
    @Volatile var evictionJob: Job? = null,
    @Volatile var state: ConversationState = ConversationState.BUSY,
)

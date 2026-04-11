package org.thingai.app.aigateway.lm.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thingai.base.log.ILog
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry tracking active and recently-completed inference jobs.
 *
 * ## Lifecycle
 * - [startJob]  — called when a message is submitted; creates a [ConversationJob] in BUSY state
 * - [onToken]   — called for each streamed token; resets the watchdog timer
 * - [onDone]    — called when inference completes; transitions to DONE, starts eviction timer
 * - [onError]   — called on inference failure or watchdog timeout; clears job → IDLE
 * - [consume]   — called by WS handler on reconnect; returns the buffered reply, clears job → IDLE
 *
 * ## Timers
 * - **Watchdog** ([WATCHDOG_MS]): started on [startJob], reset on each [onToken].
 *   If it fires, the inference is considered hung → [onError] is invoked.
 * - **Eviction TTL** ([EVICTION_TTL_MS]): started on [onDone].
 *   If no WS client calls [consume] within this window, the job is cleared silently.
 *   The reply is already persisted to DB at this point — history load covers reconnecting clients.
 *
 * ## Thread safety
 * Backed by [ConcurrentHashMap]. State transitions are @Volatile on [ConversationJob].
 */
object ConversationJobRegistry {

    private const val TAG = "ConversationJobRegistry"

    /** Milliseconds of silence (no token) before a BUSY job is declared hung. */
    const val WATCHDOG_MS = 300_000L   // 300 s

    /** Milliseconds a DONE job stays in the registry before being evicted. */
    const val EVICTION_TTL_MS = 60_000L   // 60 s

    private val jobs = ConcurrentHashMap<String, ConversationJob>()
    private val scope = CoroutineScope(Dispatchers.Default)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the current [ConversationState] for [convName].
     * Returns [ConversationState.IDLE] if no job exists.
     */
    fun getState(convName: String): ConversationState =
        jobs[convName]?.state ?: ConversationState.IDLE

    /**
     * Returns true if [convName] has an active BUSY job.
     */
    fun isBusy(convName: String): Boolean =
        jobs[convName]?.state == ConversationState.BUSY

    /**
     * Creates a new [ConversationJob] in BUSY state and starts the watchdog timer.
     *
     * @throws IllegalStateException if a job already exists for [convName].
     */
    fun startJob(convName: String): ConversationJob {
        val job = ConversationJob(convName = convName)
        val existing = jobs.putIfAbsent(convName, job)
        check(existing == null) { "Job already exists for '$convName' in state ${existing!!.state}" }
        job.watchdogJob = launchWatchdog(convName)
        ILog.i(TAG, "startJob: '$convName' BUSY")
        return job
    }

    /**
     * Called for each token received from inference.
     * Appends [token] to the reply buffer and resets the watchdog timer.
     */
    fun onToken(convName: String, token: String) {
        val job = jobs[convName] ?: return
        job.replyBuffer.append(token)
        // Reset watchdog — cancel old, start new
        job.watchdogJob?.cancel()
        job.watchdogJob = launchWatchdog(convName)
    }

    /**
     * Called when inference completes successfully.
     * Transitions job to DONE, resolves [ConversationJob.completionDeferred] with the
     * full reply, and starts the eviction timer.
     */
    fun onDone(convName: String) {
        val job = jobs[convName] ?: return
        job.watchdogJob?.cancel()
        job.state = ConversationState.DONE
        val reply = job.replyBuffer.toString()
        job.completionDeferred.complete(reply)
        job.evictionJob = launchEviction(convName)
        ILog.i(TAG, "onDone: '$convName' DONE (${reply.length} chars), eviction in ${EVICTION_TTL_MS / 1000}s")
    }

    /**
     * Called on inference failure or watchdog timeout.
     * Completes [ConversationJob.completionDeferred] exceptionally and clears the job.
     */
    fun onError(convName: String, reason: String) {
        val job = jobs.remove(convName) ?: return
        job.watchdogJob?.cancel()
        job.evictionJob?.cancel()
        job.state = ConversationState.IDLE
        if (!job.completionDeferred.isCompleted) {
            job.completionDeferred.completeExceptionally(RuntimeException(reason))
        }
        ILog.w(TAG, "onError: '$convName' → IDLE (reason: $reason)")
    }

    /**
     * Called by the WS handler when a client connects to a DONE conversation.
     * Returns the buffered reply and immediately clears the job → IDLE.
     * Returns null if no DONE job exists.
     */
    fun consume(convName: String): String? {
        val job = jobs[convName] ?: return null
        if (job.state != ConversationState.DONE) return null
        jobs.remove(convName)
        job.evictionJob?.cancel()
        ILog.i(TAG, "consume: '$convName' reply consumed, job cleared → IDLE")
        return job.replyBuffer.toString()
    }

    /**
     * Returns the [ConversationJob] for [convName] if it is currently BUSY, or null.
     * Used by the WS handler to await [ConversationJob.completionDeferred].
     */
    fun getBusyJob(convName: String): ConversationJob? {
        val job = jobs[convName] ?: return null
        return if (job.state == ConversationState.BUSY) job else null
    }

    /** Clears all jobs. Called on [org.thingai.app.aigateway.lm.LMService.stop]. */
    fun clear() {
        jobs.values.forEach { job ->
            job.watchdogJob?.cancel()
            job.evictionJob?.cancel()
            if (!job.completionDeferred.isCompleted) {
                job.completionDeferred.completeExceptionally(RuntimeException("Registry cleared"))
            }
        }
        jobs.clear()
        ILog.d(TAG, "clear: all jobs removed")
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Launches a coroutine that calls [onError] after [WATCHDOG_MS] of silence. */
    private fun launchWatchdog(convName: String): Job =
        scope.launch {
            delay(WATCHDOG_MS)
            ILog.w(TAG, "watchdog: '$convName' silent for ${WATCHDOG_MS / 1000}s — aborting job")
            onError(convName, "Inference timed out (no token for ${WATCHDOG_MS / 1000}s)")
        }

    /** Launches a coroutine that removes a DONE job after [EVICTION_TTL_MS]. */
    private fun launchEviction(convName: String): Job =
        scope.launch {
            delay(EVICTION_TTL_MS)
            val removed = jobs.remove(convName)
            if (removed != null) {
                ILog.i(TAG, "eviction: '$convName' DONE job evicted after ${EVICTION_TTL_MS / 1000}s")
            }
        }
}

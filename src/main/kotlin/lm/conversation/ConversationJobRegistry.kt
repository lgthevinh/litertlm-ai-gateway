package org.thingai.app.aigateway.lm.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thingai.base.log.ILog
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry tracking active inference jobs.
 *
 * ## Lifecycle
 * - [startJob]  — called when a message is submitted; creates a [ConversationJob] in BUSY state
 * - [onToken]   — called for each streamed token; appends to replyBuffer, emits to tokenFlow
 * - [onDone]    — called when inference completes; clears job, resolves deferred with reply text
 * - [onError]   — called on inference failure or watchdog timeout; clears job → IDLE
 *
 * ## Timers
 * - **Watchdog** ([WATCHDOG_MS]): started on [startJob], reset on each [onToken].
 *   If it fires, the inference is considered hung → [onError] is invoked.
 *
 * ## Thread safety
 * Backed by [ConcurrentHashMap]. State transitions are @Volatile on [ConversationJob].
 */
object ConversationJobRegistry {

    private const val TAG = "ConversationJobRegistry"

    /** Milliseconds of silence (no token) before a BUSY job is declared hung. */
    const val WATCHDOG_MS = 300_000L   // 300 s

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
    fun isBusy(convName: String): Boolean = jobs.containsKey(convName)

    /**
     * Creates a new [ConversationJob] in BUSY state and starts the watchdog timer.
     *
     * @throws IllegalStateException if a job already exists for [convName].
     */
    fun startJob(convName: String): ConversationJob {
        val job = ConversationJob(convName = convName)
        val existing = jobs.putIfAbsent(convName, job)
        check(existing == null) { "Job already exists for '$convName'" }
        job.watchdogJob = launchWatchdog(convName)
        ILog.i(TAG, "startJob: '$convName' BUSY")
        return job
    }

    /**
     * Called for each token received from inference.
     * Appends [token] to the reply buffer, emits to [ConversationJob.tokenFlow] for any
     * connected WS client collecting it, and resets the watchdog timer.
     * [MutableSharedFlow.tryEmit] is non-blocking and thread-safe.
     */
    fun onToken(convName: String, token: String) {
        val job = jobs[convName] ?: return
        job.replyBuffer.append(token)
        job.tokenFlow.tryEmit(token)
        // Reset watchdog — cancel old, start new
        job.watchdogJob?.cancel()
        job.watchdogJob = launchWatchdog(convName)
    }

    /**
     * Called when inference completes successfully.
     * Removes the job from the registry immediately, then resolves
     * [ConversationJob.completionDeferred] with the full reply text.
     * Any awaiting WS/REST handler receives the reply directly from the deferred.
     */
    fun onDone(convName: String) {
        val job = jobs.remove(convName) ?: return
        job.watchdogJob?.cancel()
        val reply = job.replyBuffer.toString()
        job.completionDeferred.complete(reply)
        ILog.i(TAG, "onDone: '$convName' complete (${reply.length} chars), job cleared")
    }

    /**
     * Called on inference failure or watchdog timeout.
     * Removes job and completes deferred exceptionally.
     */
    fun onError(convName: String, reason: String) {
        val job = jobs.remove(convName) ?: return
        job.watchdogJob?.cancel()
        val cause = RuntimeException(reason)
        if (!job.completionDeferred.isCompleted) {
            job.completionDeferred.completeExceptionally(cause)
        }
        ILog.w(TAG, "onError: '$convName' → IDLE (reason: $reason)")
    }

    /**
     * Returns the [ConversationJob] for [convName] if it is currently BUSY, or null.
     * Used by the WS/REST handler to await [ConversationJob.completionDeferred].
     */
    fun getBusyJob(convName: String): ConversationJob? = jobs[convName]

    /** Clears all jobs. Called on [org.thingai.app.aigateway.lm.LMService.stop]. */
    fun clear() {
        jobs.values.forEach { job ->
            job.watchdogJob?.cancel()
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
}

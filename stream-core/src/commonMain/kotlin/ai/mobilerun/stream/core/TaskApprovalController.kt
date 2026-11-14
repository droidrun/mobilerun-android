// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Push events only trigger reloads. Closing the returned handle unsubscribes. */
public fun interface ApprovalSubscription {
    /** Subscribes to changes for one task. */
    public fun subscribe(taskId: String, onChange: () -> Unit): AutoCloseable
}

/**
 * Coroutine counterpart of React's useTaskApproval. Callbacks talk to the integrator's backend.
 * Polls every three seconds after fetching unless [subscribe] is provided. Only the newest fetch
 * result applies. Every decision triggers a refetch, including errors and integrator timeouts.
 * All methods are thread-safe; [close] or scope cancellation stops work and unsubscribes.
 */
public class TaskApprovalController(
    scope: CoroutineScope,
    private val fetchApproval: suspend (taskId: String) -> PendingApproval?,
    private val sendDecision: suspend (taskId: String, approvalId: String, decision: ApprovalDecision) -> Unit,
    private val subscribe: ApprovalSubscription? = null,
    private val pollIntervalMs: Long = if (subscribe == null) 3_000 else 0,
    private val onError: (Throwable) -> Unit = {},
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + serialDispatcher(scope) + job)
    private val mutableState = MutableStateFlow(TaskApprovalState())
    /** Current approval and progress. Decision errors survive successful refetches. */
    public val state: StateFlow<TaskApprovalState> = mutableState.asStateFlow()
    private var taskId: String? = null
    private var generation = 0L
    private var latest = 0L
    private var poll: Job? = null
    private var subscription: Job? = null
    private var fetchError: Throwable? = null
    private var decideError: Throwable? = null

    init {
        require(pollIntervalMs >= 0) { "pollIntervalMs must be non-negative" }
    }

    /** Binds a task; null/empty disables work and clears all state. */
    public fun setTask(taskId: String?) {
        worker.launch {
            val next = taskId?.takeIf { it.isNotEmpty() }
            if (this@TaskApprovalController.taskId == next) return@launch
            generation++
            latest++
            poll?.cancel()
            subscription?.cancel()
            this@TaskApprovalController.taskId = next
            fetchError = null
            decideError = null
            publish { TaskApprovalState(isLoading = next != null) }
            if (next == null) return@launch
            load()
            val bound = generation
            subscription = worker.launch {
                val handle = try {
                    subscribe?.subscribe(next) {
                        worker.launch { if (isNewest(bound)) load() }
                    } ?: return@launch
                } catch (failure: Throwable) {
                    job.ensureActive()
                    if (isNewest(bound)) {
                        fetchError = failure
                        publish()
                        reportError(failure)
                    }
                    return@launch
                }
                try {
                    awaitCancellation()
                } finally {
                    runCatching { handle.close() }.onFailure(::reportError)
                }
            }
        }
    }

    /** Reloads without subscribing again; overlapping older results are discarded. */
    public fun refetch() {
        worker.launch { load() }
    }

    /** Sends one decision at a time. Expiry is display-only; the backend decides. */
    public fun decide(decision: ApprovalDecision) {
        worker.launch {
            val id = taskId ?: return@launch
            val current = state.value
            val approval = current.approval ?: return@launch
            if (current.isDeciding) return@launch
            val bound = generation
            decideError = null
            publish { it.copy(isDeciding = true, decision = decision) }
            val failure = try {
                sendDecision(id, approval.approvalId, decision)
                null
            } catch (failure: Throwable) {
                job.ensureActive()
                failure
            }
            if (!isNewest(bound)) return@launch
            decideError = failure
            publish { it.copy(isDeciding = false) }
            if (failure != null) reportError(failure)
            load()
        }
    }

    /** Stops work and closes the subscription. A closed controller cannot restart. */
    public fun close() {
        worker.cancel()
    }

    private fun isNewest(bound: Long, sequence: Long = latest): Boolean =
        job.isActive && bound == generation && sequence == latest

    private fun publish(change: (TaskApprovalState) -> TaskApprovalState = { it }) {
        mutableState.value = change(state.value).copy(error = decideError ?: fetchError)
    }

    private fun reportError(failure: Throwable) {
        runCatching { onError(failure) }
    }

    private fun load() {
        val id = taskId ?: return
        poll?.cancel()
        val bound = generation
        val sequence = ++latest
        worker.launch {
            try {
                val next = fetchApproval(id)
                if (!isNewest(bound, sequence)) return@launch
                fetchError = null
                publish { it.copy(approval = next) }
            } catch (failure: Throwable) {
                job.ensureActive()
                if (!isNewest(bound, sequence)) return@launch
                fetchError = failure
                publish()
                reportError(failure)
            } finally {
                if (isNewest(bound, sequence)) {
                    publish { it.copy(isLoading = false) }
                    if (pollIntervalMs > 0) poll = worker.launch {
                        delay(pollIntervalMs)
                        load()
                    }
                }
            }
        }
    }
}

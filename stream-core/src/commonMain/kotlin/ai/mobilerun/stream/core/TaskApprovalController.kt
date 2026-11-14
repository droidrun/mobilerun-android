// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Snapshot of approval loading and decision progress. Decision errors take precedence over fetch errors. */
public data class TaskApprovalState(
    public val approval: PendingApproval? = null,
    public val isLoading: Boolean = false,
    public val error: Throwable? = null,
    public val isDeciding: Boolean = false,
    public val decision: ApprovalDecision? = null,
)

/** Push events are hints to reload, never approval data. The returned callback unsubscribes. */
public typealias ApprovalSubscription = (taskId: String, onChange: () -> Unit) -> (() -> Unit)

/**
 * Coroutine counterpart of React's useTaskApproval. All callbacks talk to the integrator's backend,
 * never directly to an API with an embedded key. Polls every three seconds after a completed fetch
 * unless [subscribe] is provided. Overlapping fetches keep only the newest result. Every decision,
 * successful or failed (including HTTP 404/409), triggers a refetch and preserves its error.
 *
 * Call [setTask] to start/change/disable a task; [close] or scope cancellation unsubscribes and stops.
 * All methods are safe from any thread. Callbacks run on the serial dispatcher derived from [scope].
 */
public class TaskApprovalController(
    scope: CoroutineScope,
    private val fetchApproval: suspend (taskId: String) -> PendingApproval?,
    private val decide: suspend (taskId: String, approvalId: String, decision: ApprovalDecision) -> Unit,
    private val subscribe: ApprovalSubscription? = null,
    private val pollIntervalMs: Long = if (subscribe == null) 3_000 else 0,
    private val onError: (Throwable) -> Unit = {},
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val serial = serialDispatcher(scope)
    private val worker = CoroutineScope(scope.coroutineContext + serial + job)
    private val mutableState = MutableStateFlow(TaskApprovalState())
    /** The current approval and operation state. */
    public val state: StateFlow<TaskApprovalState> = mutableState.asStateFlow()
    private var taskId: String? = null
    private var generation = 0L
    private var latest = 0L
    private var poll: Job? = null
    private var unsubscribe: (() -> Unit)? = null
    private var fetchError: Throwable? = null
    private var decideError: Throwable? = null

    init {
        require(pollIntervalMs >= 0) { "pollIntervalMs must be non-negative" }
        job.invokeOnCompletion {
            CoroutineScope(serial).launch { unsubscribe?.invoke(); unsubscribe = null }
        }
    }

    /** Binds a task. Changing it clears approval, errors and decision progress; disabled tasks do no work. */
    public fun setTask(taskId: String, enabled: Boolean = true) {
        worker.launch {
            val next = taskId.takeIf { enabled && it.isNotEmpty() }
            if (this@TaskApprovalController.taskId == next) return@launch
            generation++
            latest++
            poll?.cancel()
            unsubscribe?.invoke()
            unsubscribe = null
            this@TaskApprovalController.taskId = next
            fetchError = null
            decideError = null
            mutableState.value = TaskApprovalState(isLoading = next != null)
            if (next == null) return@launch
            load()
            val bound = generation
            unsubscribe = subscribe?.invoke(next) {
                worker.launch { if (bound == generation) load() }
            }
        }
    }

    /** Reloads without re-subscribing. Older overlapping results are discarded. */
    public fun refetch() { worker.launch { load() } }

    /** Forwards one decision at a time. No local expiry gate: the server decides. */
    public fun decide(decision: ApprovalDecision) {
        worker.launch {
            val id = taskId ?: return@launch
            val current = state.value
            val approval = current.approval ?: return@launch
            if (current.isDeciding) return@launch
            val bound = generation
            decideError = null
            mutableState.value = current.copy(isDeciding = true, decision = decision, error = fetchError)
            val failure = try {
                decide(id, approval.approvalId, decision)
                null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            if (bound != generation) return@launch
            decideError = failure
            mutableState.value = state.value.copy(isDeciding = false, error = decideError ?: fetchError)
            if (failure != null) onError(failure)
            load()
        }
    }

    /** Stops polling, subscriptions and callbacks. A closed controller cannot restart. */
    public fun close() { worker.cancel() }

    private fun load() {
        val id = taskId ?: return
        poll?.cancel()
        val bound = generation
        val sequence = ++latest
        worker.launch {
            try {
                val next = fetchApproval(id)
                if (bound != generation || sequence != latest) return@launch
                fetchError = null
                mutableState.value = state.value.copy(approval = next, error = decideError)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (bound != generation || sequence != latest) return@launch
                fetchError = e
                mutableState.value = state.value.copy(error = decideError ?: e)
                onError(e)
            } finally {
                if (bound == generation && sequence == latest) {
                    mutableState.value = state.value.copy(isLoading = false)
                    if (pollIntervalMs > 0) poll = worker.launch { delay(pollIntervalMs); load() }
                }
            }
        }
    }
}

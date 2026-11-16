// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.core

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

private fun approval(id: String = "one") = PendingApproval(id, "payment_card", emptyMap(), "2026-01-01T00:00:00Z")

class TaskApprovalControllerTest {
    @Test fun pollsOnlyAfterCompletionAndStopsWhenDisabled() = runTest {
        var calls = 0
        val c = TaskApprovalController(backgroundScope, {
            calls++
            approval()
        }, { _, _, _ -> })
        c.setTask("task")
        runCurrent()
        assertEquals(1, calls)
        assertFalse(c.state.value.isLoading)
        advanceTimeBy(2999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        c.setTask(null)
        runCurrent()
        advanceTimeBy(9000)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(TaskApprovalState(approval = null), c.state.value)
    }
    @Test fun pushDisablesDefaultPollingAndRefetchDoesNotResubscribe() = runTest {
        var changed: () -> Unit = {}
        var subscriptions = 0
        var unsubscribed = 0
        var calls = 0
        val c = TaskApprovalController(backgroundScope, {
            calls++
            approval()
        }, { _, _, _ -> },
            subscribe = { _, change ->
                subscriptions++
                changed = change
                AutoCloseable { unsubscribed++ }
            })
        c.setTask("task")
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, calls)
        changed()
        c.refetch()
        runCurrent()
        assertEquals(3, calls)
        assertEquals(1, subscriptions)
        c.close()
        runCurrent()
        assertEquals(1, unsubscribed)
        changed()
        runCurrent()
        assertEquals(3, calls)
    }
    @Test fun explicitPollingCanCoexistWithPush() = runTest {
        var calls = 0
        val c = TaskApprovalController(backgroundScope, {
            calls++
            null
        }, { _, _, _ -> }, subscribe = { _, _ -> AutoCloseable {} }, pollIntervalMs = 100)
        c.setTask("task")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, calls)
    }
    @Test fun overlappingFetchesKeepNewest() = runTest {
        val requests = mutableListOf<CompletableDeferred<PendingApproval?>>()
        val c = TaskApprovalController(backgroundScope, { CompletableDeferred<PendingApproval?>().also { requests += it }.await() }, { _, _, _ -> })
        c.setTask("task")
        runCurrent()
        c.refetch()
        runCurrent()
        requests[1].complete(approval("new"))
        runCurrent()
        requests[0].complete(approval("old"))
        runCurrent()
        assertEquals("new", c.state.value.approval?.approvalId)
    }
    @Test fun taskChangeAndDisableDropLateFetchesAndOldPush() = runTest {
        val gates = mutableMapOf<String, CompletableDeferred<PendingApproval?>>()
        val listeners = mutableListOf<() -> Unit>()
        val c = TaskApprovalController(backgroundScope,
            { id -> gates.getOrPut(id) { CompletableDeferred() }.await() }, { _, _, _ -> },
            subscribe = { _, change ->
                listeners += change
                AutoCloseable {}
            })
        c.setTask("first")
        runCurrent()
        c.setTask("second")
        runCurrent()
        gates.getValue("first").complete(approval("old"))
        runCurrent()
        assertNull(c.state.value.approval)
        assertTrue(c.state.value.isLoading)
        gates.getValue("second").complete(approval("new"))
        runCurrent()
        assertEquals("new", c.state.value.approval?.approvalId)
        c.setTask(null)
        runCurrent()
        listeners.first()()
        runCurrent()
        assertEquals(TaskApprovalState(approval = null), c.state.value)
    }
    @Test fun decisionsAreDeduplicatedAndAlwaysRefetchEvenWhenExpired() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Triple<String, String, ApprovalDecision>>()
        var calls = 0
        val c = TaskApprovalController(backgroundScope, {
            calls++
            if (calls == 1) approval() else null
        },
            { task, id, decision ->
                sent += Triple(task, id, decision)
                gate.await()
            })
        c.setTask("task")
        runCurrent()
        c.decide(ApprovalDecision.Approve)
        c.decide(ApprovalDecision.Deny)
        runCurrent()
        assertEquals(listOf(Triple("task", "one", ApprovalDecision.Approve)), sent)
        assertTrue(c.state.value.isDeciding)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertNull(c.state.value.approval)
        assertFalse(c.state.value.isDeciding)
        assertEquals(ApprovalDecision.Approve, c.state.value.decision)
    }
    @Test fun notFoundAndConflictPreserveDecisionErrorAfterRefetch() = runTest {
        for (status in listOf(404, 409)) {
            val failure = IllegalStateException("HTTP $status")
            val errors = mutableListOf<Throwable>()
            var calls = 0
            var fail = true
            val c = TaskApprovalController(backgroundScope, {
                calls++
                approval()
            }, { _, _, _ -> if (fail) throw failure }, onError = { errors += it })
            c.setTask("task")
            runCurrent()
            c.decide(ApprovalDecision.Deny)
            runCurrent()
            assertEquals(2, calls)
            assertSame(failure, c.state.value.error)
            assertEquals(listOf<Throwable>(failure), errors)
            fail = false
            c.decide(ApprovalDecision.Approve)
            runCurrent()
            assertEquals(3, calls)
            assertNull(c.state.value.error)
            c.close()
        }
    }
    @Test fun fetchFailureRecoversAndDoesNotOverwriteDecisionError() = runTest {
        val fetchError = IllegalStateException("fetch")
        val decisionError = IllegalStateException("decision")
        var fail = true
        val c = TaskApprovalController(backgroundScope, { if (fail) throw fetchError else approval() }, { _, _, _ -> throw decisionError })
        c.setTask("task")
        runCurrent()
        assertSame(fetchError, c.state.value.error)
        assertFalse(c.state.value.isLoading)
        fail = false
        c.refetch()
        runCurrent()
        assertNull(c.state.value.error)
        fail = true
        c.decide(ApprovalDecision.Deny)
        runCurrent()
        assertSame(decisionError, c.state.value.error)
        fail = false
        c.refetch()
        runCurrent()
        assertSame(decisionError, c.state.value.error)
        c.setTask("other")
        runCurrent()
        assertNull(c.state.value.error)
        assertNull(c.state.value.decision)
    }
    @Test fun lateDecisionDoesNotMutateNewTaskOrTriggerItsRefetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val c = TaskApprovalController(backgroundScope, { id ->
            calls++
            approval(id)
        }, { _, _, _ ->
            gate.await()
            error("old")
        })
        c.setTask("first")
        runCurrent()
        c.decide(ApprovalDecision.Deny)
        runCurrent()
        c.setTask("second")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("second", c.state.value.approval?.approvalId)
        assertNull(c.state.value.error)
        assertFalse(c.state.value.isDeciding)
    }
    @Test fun parentCancellationUnsubscribes() = runTest {
        var removed = false
        val parent = kotlinx.coroutines.Job()
        val c = TaskApprovalController(kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + parent), { null }, { _, _, _ -> },
            subscribe = { _, _ -> AutoCloseable { removed = true } })
        c.setTask("task")
        runCurrent()
        parent.cancel()
        runCurrent()
        assertTrue(removed)
    }
    @Test fun fetchTimeoutIsAnErrorAndTheNextFetchRecovers() = runTest {
        var timeout = true
        val c = TaskApprovalController(backgroundScope,
            { if (timeout) withTimeout(10) { awaitCancellation() } else approval() },
            { _, _, _ -> })
        c.setTask("task")
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertIs<TimeoutCancellationException>(c.state.value.error)
        assertFalse(c.state.value.isLoading)
        timeout = false
        c.refetch()
        runCurrent()
        assertNull(c.state.value.error)
        assertEquals("one", c.state.value.approval?.approvalId)
    }

    @Test fun decisionTimeoutClearsProgressAndRefetches() = runTest {
        var fetches = 0
        val c = TaskApprovalController(backgroundScope,
            {
                fetches++
                approval()
            },
            { _, _, _ -> withTimeout(10) { awaitCancellation() } })
        c.setTask("task")
        runCurrent()
        c.decide(ApprovalDecision.Approve)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertIs<TimeoutCancellationException>(c.state.value.error)
        assertFalse(c.state.value.isDeciding)
        assertEquals(2, fetches)
    }

    @Test fun integratorCancellationAndThrowingErrorCallbackCannotWedgeDecisions() = runTest {
        var fail = true
        val c = TaskApprovalController(backgroundScope, { approval() },
            { _, _, _ -> if (fail) throw kotlinx.coroutines.CancellationException("integrator") },
            onError = { error("bad error handler") })
        c.setTask("task")
        runCurrent()
        c.decide(ApprovalDecision.Deny)
        runCurrent()
        assertFalse(c.state.value.isDeciding)
        assertIs<kotlinx.coroutines.CancellationException>(c.state.value.error)
        fail = false
        c.decide(ApprovalDecision.Approve)
        runCurrent()
        assertNull(c.state.value.error)
    }

    @Test fun subscriptionAndUnsubscribeFailuresAreGuarded() = runTest {
        var failSubscribe = true
        val failures = mutableListOf<Throwable>()
        val c = TaskApprovalController(backgroundScope, { approval() }, { _, _, _ -> },
            subscribe = { _, _ ->
                if (failSubscribe) error("subscribe")
                AutoCloseable { error("unsubscribe") }
            }, onError = {
                failures += it
                error("onError")
            })
        c.setTask("first")
        runCurrent()
        assertEquals("subscribe", c.state.value.error?.message)
        failSubscribe = false
        c.setTask("second")
        runCurrent()
        c.close()
        runCurrent()
        assertEquals(listOf("subscribe", "unsubscribe"), failures.map { it.message })
    }

    @Test fun failedSubscriptionReportsErrorAndPollsEveryThreeSeconds() = runTest {
        val failure = IllegalStateException("subscribe")
        val errors = mutableListOf<Throwable>()
        var fetches = 0
        val c = TaskApprovalController(backgroundScope, {
            fetches++
            approval()
        }, { _, _, _ -> }, subscribe = { _, _ -> throw failure }, onError = { errors += it })
        c.setTask("task")
        runCurrent()
        assertSame(failure, c.state.value.error)
        assertEquals(listOf<Throwable>(failure), errors)
        assertEquals(1, fetches)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fetches)
        assertNull(c.state.value.error)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3, fetches)
        c.close()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3, fetches)
    }

    @Test fun failedSubscriptionWaitsForInitialFetchBeforePolling() = runTest {
        val initial = CompletableDeferred<PendingApproval?>()
        var fetches = 0
        val c = TaskApprovalController(backgroundScope, {
            fetches++
            initial.await()
        }, { _, _, _ -> }, subscribe = { _, _ -> error("subscribe") })
        c.setTask("task")
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, fetches)
        initial.complete(approval())
        runCurrent()
        assertEquals("one", c.state.value.approval?.approvalId)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fetches)
    }

}

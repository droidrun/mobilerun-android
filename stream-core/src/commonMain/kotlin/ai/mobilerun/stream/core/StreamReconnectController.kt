// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.random.Random

/** Where a stream is in its connect and recovery cycle. */
public enum class StreamReconnectStatus {
    /** A fresh attempt is negotiating. */
    Connecting,

    /** Media is flowing. */
    Connected,

    /** An attempt failed; waiting out the backoff or retrying. */
    Reconnecting,

    /** The retry budget is exhausted; only [StreamReconnectController.retry] or [StreamReconnectController.handleWake] restart it. */
    Unavailable,
}

/**
 * The controller state visible to the UI.
 *
 * @property status the current [StreamReconnectStatus].
 * @property attempt consecutive failed attempts so far: the 1-based number of the upcoming (or
 *   in-flight) reconnect attempt. 0 while connecting fresh or after the stability window refilled the budget.
 */
public data class StreamReconnectSnapshot(val status: StreamReconnectStatus, val attempt: Int)

/** A timer returned by [ReconnectScheduler.schedule]. */
public fun interface ScheduledTask {
    /** Cancels the timer. Must be harmless if it already fired or was cancelled. */
    public fun cancel()
}

/**
 * Source of time for the controller. The core owns no real timers: the embedding app supplies
 * one backed by its main loop, and tests supply a fake clock.
 */
public fun interface ReconnectScheduler {
    /**
     * Runs [action] once after [delayMs] milliseconds. Timers due at the same time must fire in the
     * order they were scheduled. [action] must run on the same serial context that calls the controller.
     */
    public fun schedule(delayMs: Long, action: () -> Unit): ScheduledTask
}

/**
 * Pure reconnect state machine for one device stream; behaves exactly like the reference
 * implementation in `@mobilerun/react` (see the conformance fixtures).
 *
 * Contract with the host:
 * - Call [attemptMounted] after every (re)start of the stream connection and on enable. It arms the
 *   connect watchdog.
 * - [onRemount] asks the host to tear down and restart the connection; the restart calls
 *   [attemptMounted] back.
 * - [handleConnected] / [handleDisconnected] mirror the peer connection state; [handleFailure] is
 *   the terminal-failure signal (socket died, peer connection failed, negotiation threw).
 *
 * Policy: a failed attempt schedules the next with exponential backoff (see [ReconnectPolicy]).
 * After [ReconnectPolicy.MAX_ATTEMPTS] consecutive failures the stream is [StreamReconnectStatus.Unavailable]
 * and retries stop. Holding a connection for [ReconnectPolicy.STABILITY_MS] refills the budget.
 * [handleWake] short-circuits a pending backoff wait and starts a fresh cycle when unavailable.
 *
 * Not thread-safe: use it from one serial context, the one the [scheduler] runs actions on.
 *
 * @param onRemount restart the stream connection.
 * @param onSnapshot status or attempt changed.
 * @param onHeal a reconnect cycle starts: refetch stream credentials. Called once per recovery cycle.
 * @param scheduler time source.
 * @param random jitter source returning a value in `[0, 1)`.
 */
public class StreamReconnectController(
    private val onRemount: () -> Unit,
    private val onSnapshot: (StreamReconnectSnapshot) -> Unit,
    private val onHeal: () -> Unit,
    private val scheduler: ReconnectScheduler,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private var enabled = false
    private var status = StreamReconnectStatus.Connecting
    private var attempt = 0

    // Whether the current mount ever reached connected. Gates handleDisconnected: the peer
    // connection reports non-connected states during normal negotiation, which are not failures.
    private var everConnectedThisEpoch = false

    // One failure per mount: a dying attempt fires several signals that collapse into one backoff step.
    private var failureLatched = false

    // One heal per recovery cycle, tracked explicitly: a fresh cycle heals with attempt reset to 0,
    // so its first failed retry would otherwise heal again at attempt 1.
    private var healedThisCycle = false
    private var watchdogTimer: ScheduledTask? = null
    private var backoffTimer: ScheduledTask? = null
    private var stabilityTimer: ScheduledTask? = null

    /** The current state. */
    public val snapshot: StreamReconnectSnapshot
        get() = StreamReconnectSnapshot(status, attempt)

    /** The stream connection (re)started; arms the connect watchdog. */
    public fun attemptMounted() {
        enabled = true
        // A mount during a backoff wait is stale: the scheduled attempt owns the next watchdog.
        if (backoffTimer != null || status == StreamReconnectStatus.Unavailable) return
        watchdogTimer = cancel(watchdogTimer)
        everConnectedThisEpoch = false
        failureLatched = false
        if (status == StreamReconnectStatus.Connected) {
            // The restart tossed the live connection (credential rotation); the new one is negotiating.
            stabilityTimer = cancel(stabilityTimer)
            status = StreamReconnectStatus.Connecting
            emit()
        }
        watchdogTimer = scheduler.schedule(ReconnectPolicy.CONNECT_TIMEOUT_MS) {
            watchdogTimer = null
            countFailure()
        }
    }

    /** There is no stream target anymore: stops all timers and resets the counters. */
    public fun setDisabled() {
        enabled = false
        clearAllTimers()
        everConnectedThisEpoch = false
        failureLatched = false
        healedThisCycle = false
        if (status != StreamReconnectStatus.Connecting || attempt != 0) {
            status = StreamReconnectStatus.Connecting
            attempt = 0
            emit()
        }
    }

    /** The peer connection reached the connected state. */
    public fun handleConnected() {
        if (!enabled) return
        watchdogTimer = cancel(watchdogTimer)
        // The old attempt recovered on its own (transient dip); the scheduled remount would only interrupt it again.
        backoffTimer = cancel(backoffTimer)
        everConnectedThisEpoch = true
        failureLatched = false
        if (status != StreamReconnectStatus.Connected) {
            status = StreamReconnectStatus.Connected
            emit()
        }
        stabilityTimer = cancel(stabilityTimer)
        stabilityTimer = scheduler.schedule(ReconnectPolicy.STABILITY_MS) {
            stabilityTimer = null
            // The cycle is over: the next failure run heals anew.
            healedThisCycle = false
            if (attempt != 0) {
                attempt = 0
                emit()
            }
        }
    }

    /** The peer connection left the connected state. Only counts as a failure after it had connected once. */
    public fun handleDisconnected() {
        if (!enabled) return
        if (!everConnectedThisEpoch) return
        if (failureLatched) return
        countFailure()
    }

    /** Terminal failure of the current attempt: the socket died, the peer connection failed or negotiation threw. */
    public fun handleFailure() {
        if (!enabled) return
        if (failureLatched) return
        countFailure()
    }

    /**
     * Whether a stream-target change (URL or token rotation) must be held back instead of applied
     * now. True while waiting out a backoff (the scheduled attempt picks up the fresh target by
     * itself) and while unavailable (retry and wake own recovery there). Everywhere else the new
     * target applies immediately.
     */
    public fun shouldDeferTargetChange(): Boolean =
        backoffTimer != null || status == StreamReconnectStatus.Unavailable

    /** The app became visible or the device came back online. */
    public fun handleWake() {
        if (!enabled) return
        if (status == StreamReconnectStatus.Unavailable) {
            freshCycle()
            return
        }
        if (backoffTimer != null) {
            // Don't sit out a (possibly throttled) background wait now that the user is looking.
            backoffTimer = cancel(backoffTimer)
            onRemount()
        }
    }

    /** Manual retry (an "unavailable" screen's button). Only acts while unavailable. */
    public fun retry() {
        if (!enabled) return
        if (status == StreamReconnectStatus.Unavailable) freshCycle()
    }

    /** Stops all timers; the controller is not meant to be used afterwards. */
    public fun dispose() {
        enabled = false
        clearAllTimers()
    }

    private fun emit() = onSnapshot(StreamReconnectSnapshot(status, attempt))

    private fun cancel(task: ScheduledTask?): ScheduledTask? {
        task?.cancel()
        return null
    }

    private fun clearAllTimers() {
        watchdogTimer = cancel(watchdogTimer)
        backoffTimer = cancel(backoffTimer)
        stabilityTimer = cancel(stabilityTimer)
    }

    private fun countFailure() {
        // A wait is already scheduled (or we already gave up), e.g. a stale watchdog. Never stack timers.
        if (backoffTimer != null || status == StreamReconnectStatus.Unavailable) return
        failureLatched = true
        watchdogTimer = cancel(watchdogTimer)
        stabilityTimer = cancel(stabilityTimer)
        attempt += 1
        if (attempt > ReconnectPolicy.MAX_ATTEMPTS) {
            status = StreamReconnectStatus.Unavailable
            emit()
            return
        }
        status = StreamReconnectStatus.Reconnecting
        emit()
        // Refetch credentials once per recovery cycle, not per attempt: heals trigger network
        // refetches, and per-attempt heals would multiply into a request storm during a shared outage.
        if (!healedThisCycle) {
            healedThisCycle = true
            onHeal()
        }
        backoffTimer = scheduler.schedule(ReconnectPolicy.delayMs(attempt, random())) {
            backoffTimer = null
            onRemount()
        }
    }

    private fun freshCycle() {
        attempt = 0
        failureLatched = false
        everConnectedThisEpoch = false
        // This heal covers the whole restarted cycle: a failure of the restarted attempt must not heal again.
        healedThisCycle = true
        status = StreamReconnectStatus.Reconnecting
        emit()
        onHeal()
        onRemount()
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** Deterministic scheduler: due timers fire in due-time order, ties in scheduling order. */
internal class FakeClock : ReconnectScheduler {
    private class Timer(val due: Long, val order: Long, val action: () -> Unit) {
        var done = false
    }

    private val timers = mutableListOf<Timer>()
    private var counter = 0L

    var now: Long = 0
        private set

    /** Timers still scheduled: neither fired nor cancelled. */
    val pendingTimers: Int get() = timers.count { !it.done }

    override fun schedule(delayMs: Long, action: () -> Unit): ScheduledTask {
        val timer = Timer(now + delayMs, counter++, action)
        timers += timer
        return ScheduledTask { timer.done = true }
    }

    /** Moves the clock to [time] (never backwards), firing every timer due on the way, including new ones. */
    fun advanceTo(time: Long) {
        while (true) {
            val next = timers.filter { !it.done && it.due <= time }
                .minWithOrNull(compareBy<Timer> { it.due }.thenBy { it.order }) ?: break
            now = maxOf(now, next.due)
            next.done = true
            next.action()
        }
        now = maxOf(now, time)
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the controller that the shared scenarios do not cover. */
class StreamReconnectControllerTest {
    private val clock = FakeClock()
    private var heals = 0
    private var remounts = 0
    private val snapshots = mutableListOf<StreamReconnectSnapshot>()
    private val controller = StreamReconnectController(
        onRemount = { remounts += 1 },
        onSnapshot = { snapshots += it },
        onHeal = { heals += 1 },
        scheduler = clock,
        random = { 0.5 },
    )

    @Test
    fun everyEntryPointDoesNothingAfterDispose() {
        controller.attemptMounted()
        controller.dispose()
        val before = controller.snapshot

        controller.attemptMounted()
        controller.handleConnected()
        controller.handleDisconnected()
        controller.handleFailure()
        controller.handleWake()
        controller.retry()
        controller.disable()
        clock.advanceTo(10 * 60_000)

        assertEquals(0, clock.pendingTimers)
        assertEquals(before, controller.snapshot)
        assertEquals(0, heals)
        assertEquals(0, remounts)
        assertEquals(emptyList(), snapshots)
    }

    @Test
    fun attemptMountedAfterDisposeDoesNotArmTheWatchdog() {
        controller.dispose()
        controller.attemptMounted()
        clock.advanceTo(ReconnectPolicy.CONNECT_TIMEOUT_MS)
        assertEquals(StreamReconnectSnapshot(StreamReconnectStatus.Connecting, 0), controller.snapshot)
    }

    @Test
    fun disableResetsStateAndStopsTimers() {
        controller.attemptMounted()
        controller.handleFailure()
        controller.disable()
        assertEquals(0, clock.pendingTimers)
        assertEquals(StreamReconnectSnapshot(StreamReconnectStatus.Connecting, 0), controller.snapshot)
    }
}

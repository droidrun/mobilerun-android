// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

class DeviceStreamCredentialsTest {
    @Test fun pollsAtThreeSecondsAndRefetchesOnEveryCall() = runTest {
        var calls = 0
        val ready = StreamCredentials("wss://example.test/stream", "temporary")
        val helper = DeviceStreamCredentials({ calls++; if (calls > 2) ready else null })
        val result = async { helper.provider.credentials() }
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(2999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(3000); runCurrent(); assertSame(ready, result.await())
        assertSame(ready, helper.provider.credentials()); assertEquals(4, calls)
    }
    @Test fun cancellationStopsPolling() = runTest {
        var calls = 0
        val helper = DeviceStreamCredentials({ calls++; null })
        val result = async { helper.provider.credentials() }
        runCurrent(); result.cancel(); advanceTimeBy(6000); runCurrent(); assertEquals(1, calls)
    }
}

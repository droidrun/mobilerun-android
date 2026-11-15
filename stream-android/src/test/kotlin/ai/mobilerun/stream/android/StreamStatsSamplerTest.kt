// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StreamStatsSamplerTest {
    @Test fun samplingRequiresConnectedVisibleSubscriberAndResetsEveryBaseline() = runTest {
        val status = MutableStateFlow<StreamStatus>(StreamStatus.Connected)
        val visible = MutableStateFlow(true)
        var calls = 0
        val sampler = StreamStatsSampler(backgroundScope, status, visible) { parser ->
            calls++
            parser.parse(listOf(StatsRecord("video", "inbound-rtp", calls * 1_000_000.0,
                mapOf("kind" to "video", "bytesReceived" to calls * 1000))), null)
        }
        advanceTimeBy(3000); runCurrent()
        assertEquals(0, calls)
        var observer = backgroundScope.launch { sampler.stats.collect() }
        runCurrent()
        assertEquals(1, calls)
        assertNull(sampler.stats.value?.bitrateKbps)
        advanceTimeBy(1000); runCurrent()
        assertNotNull(sampler.stats.value?.bitrateKbps)
        visible.value = false; runCurrent()
        assertNull(sampler.stats.value)
        val paused = calls
        advanceTimeBy(3000); runCurrent()
        assertEquals(paused, calls)
        visible.value = true; runCurrent()
        assertNull(sampler.stats.value?.bitrateKbps)
        observer.cancel(); runCurrent()
        assertNull(sampler.stats.value)
        val unobserved = calls
        advanceTimeBy(3000); runCurrent()
        assertEquals(unobserved, calls)
        observer = backgroundScope.launch { sampler.stats.collect() }; runCurrent()
        assertNull(sampler.stats.value?.bitrateKbps)
        for (exit in listOf(StreamStatus.Reconnecting(1), StreamStatus.Unavailable, StreamStatus.Connecting)) {
            status.value = exit; runCurrent()
            assertNull(sampler.stats.value)
            val stopped = calls
            advanceTimeBy(1000); runCurrent()
            assertEquals(stopped, calls)
            status.value = StreamStatus.Connected; runCurrent()
            assertNull(sampler.stats.value?.bitrateKbps)
        }
        sampler.close(); runCurrent()
        assertNull(sampler.stats.value)
        observer.cancel()
    }

    @Test fun failedNativeTickDoesNotCrashHostAndNextTickRecovers() = runTest {
        var calls = 0
        val sampler = StreamStatsSampler(backgroundScope, MutableStateFlow(StreamStatus.Connected),
            MutableStateFlow(true)) { parser ->
            if (++calls == 1) error("native getStats failed")
            parser.parse(emptyList(), null)
        }
        backgroundScope.launch { sampler.stats.collect() }
        runCurrent()
        assertNull(sampler.stats.value)
        advanceTimeBy(1000); runCurrent()
        assertNotNull(sampler.stats.value)
        sampler.close()
    }
}

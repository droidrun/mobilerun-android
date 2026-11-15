// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.StreamStats
import ai.mobilerun.stream.core.StreamStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sampling has the same serial context as the session, with no native work while unobserved. */
internal class StreamStatsSampler(
    scope: CoroutineScope,
    status: StateFlow<StreamStatus>,
    visible: StateFlow<Boolean>,
    read: suspend (StreamStatsParser) -> StreamStats?,
) {
    private val mutable = MutableStateFlow<StreamStats?>(null)
    val stats: StateFlow<StreamStats?> = mutable.asStateFlow()
    private val job = scope.launch {
        combine(status, visible, mutable.subscriptionCount) { state, shown, count ->
            state == StreamStatus.Connected && shown && count > 0
        }.distinctUntilChanged().collectLatest { enabled ->
            mutable.value = null
            if (enabled) {
                var parser = StreamStatsParser()
                try {
                    while (currentCoroutineContext().isActive) {
                        try {
                            mutable.value = read(parser)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A native report/parser failure must not cancel the caller's scope.
                            mutable.value = null
                            parser = StreamStatsParser()
                        }
                        delay(1000)
                    }
                } finally {
                    mutable.value = null
                }
            }
        }
    }

    fun close() {
        job.cancel()
        mutable.value = null
    }
}

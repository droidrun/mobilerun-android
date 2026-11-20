// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockingTokenProviderTest {
    @Test
    fun blockingProviderCannotDelayDeadlineOrClose() = runBlocking {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val blocked = CountDownLatch(1)
            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val h = Harness(TestScope(), StreamCredentials("wss://stream.test/a", tokenProvider = {
                started.complete(Unit)
                try {
                    blocked.await()
                    "late-key"
                } finally {
                    finished.complete(Unit)
                }
            }), hostContext = dispatcher)
            try {
                h.engine.start()
                withTimeout(2_000) { started.await() }
                assertEquals(StreamStatus.Connecting, h.status)
                withTimeout(12_000) {
                    h.engine.status.first { it == StreamStatus.Reconnecting(1) }
                }
                val engineJob = h.host.coroutineContext[Job]!!.children.single()
                h.engine.close()
                withTimeout(1_000) { engineJob.join() }
                assertEquals(1L, blocked.count)
                assertTrue(h.signalling.sockets.isEmpty())
            } finally {
                h.engine.close()
                h.host.cancel()
                blocked.countDown()
                withTimeout(2_000) { finished.await() }
            }
        }
    }
}

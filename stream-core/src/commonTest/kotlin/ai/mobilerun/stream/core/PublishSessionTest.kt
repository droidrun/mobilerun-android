// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PublishSessionTest {
    @Test fun forbiddenReportsPublishEnvelopeWithoutAConnectionFailure() = runTest {
        val h = Harness(this, publishCapabilities = DevicePublishCapabilities(true, true, "direct"))
        val errors = mutableListOf<StreamError>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.errors.collect { errors += it } }
        h.connect()
        val start = async { runCatching { h.engine.startPublish(PublishOptions(true)) } }
        runCurrent()
        h.socket.serverSends("""{"type":"publishState","mediaSessionId":"${h.engine.publishState.value.mediaSessionId}","state":"rejected","reason":"forbidden"}""")
        runCurrent()
        assertIs<PublishException>(start.await().exceptionOrNull())
        assertEquals(StreamStatus.Connected, h.status)
        assertEquals(1, errors.size)
        assertEquals(StreamErrorReason.PublishNotAllowed, errors.single().reason)
        assertEquals("publish", errors.single().operation)
        assertEquals(h.engine.publishState.value.mediaSessionId, errors.single().mediaSessionId)
        assertEquals("forbidden", errors.single().serverReason)
        assertEquals(0, errors.single().attempt)
        assertTrue(errors.single().final)
        assertTrue(h.peer.publishCapture.stops > 0)
    }

    @Test fun signalingLossStopsCaptureBeforeViewerReconnectAndNeverResumesIt() = runTest {
        var tokenCalls = 0
        val h = Harness(this, StreamCredentials("wss://stream.test/a", tokenProvider = { "fresh-${++tokenCalls}" }),
            publishCapabilities = DevicePublishCapabilities(true, false, "direct"))
        h.connect()
        val start = async { h.engine.startPublish(PublishOptions(true)) }
        runCurrent()
        h.socket.serverSends("""{"type":"publishState","mediaSessionId":"${h.engine.publishState.value.mediaSessionId}","state":"active"}""")
        runCurrent()
        start.await()
        val oldPeer = h.peer
        h.socket.serverCloses()
        runCurrent()
        assertTrue(oldPeer.publishCapture.stops > 0)
        assertEquals("disconnected", h.engine.publishState.value.reason)
        h.advance(ReconnectPolicy.delayMs(1, .5))
        h.connect()
        assertEquals(2, tokenCalls)
        assertEquals(0, h.peer.captureCount)
        assertTrue(h.socket.framesOf("publishStart").isEmpty())
    }

    @Test fun samePeerIceRefreshPreservesPublicationAndCloseReleasesIt() = runTest {
        val h = Harness(this, publishCapabilities = DevicePublishCapabilities(true, false, "direct"))
        h.connect()
        val start = async { h.engine.startPublish(PublishOptions(true)) }
        runCurrent()
        val id = h.engine.publishState.value.mediaSessionId
        h.socket.serverSends("""{"type":"publishState","mediaSessionId":"$id","state":"active"}""")
        runCurrent()
        start.await()
        h.socket.serverSends("""{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}""")
        runCurrent()
        assertEquals(id, h.engine.publishState.value.mediaSessionId)
        assertEquals(PublishPhase.Active, h.engine.publishState.value.phase)
        assertEquals(0, h.peer.publishCapture.stops)
        assertEquals(1, h.socket.framesOf("publishStart").size)
        h.engine.close()
        runCurrent()
        assertTrue(h.peer.publishCapture.stops > 0)
        assertEquals("stopped_by_client", h.engine.publishState.value.reason)
        val closed = assertFailsWith<PublishException> { h.engine.startPublish(PublishOptions(true)) }
        assertEquals("session_closed", closed.failure.detail)
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.*
import ai.mobilerun.stream.protocol.IceServer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublishLifecycleTest {
    @Test fun headlessSessionStopsCaptureWhenProcessGoesToBackground() = runTest {
        var stops = 0
        var permissionCalls = 0
        var permissionOnMain = false
        val capture = object : PublishCapture {
            override suspend fun attach(kind: PublishKind) = Unit
            override fun stop() { stops++ }
            override fun detach() = Unit
        }
        val peer = object : PeerTransport, PublishPeerTransport {
            override val publishKinds = setOf(PublishKind.Audio)
            override val connectionState = PeerConnectionState.Connected
            override suspend fun capture(options: PublishOptions, onEnded: () -> Unit) = capture
            override suspend fun createOffer(iceRestart: Boolean) = "offer"
            override suspend fun setRemoteAnswer(sdp: String) = Unit
            override suspend fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) = Unit
            override fun setIceServers(iceServers: List<IceServer>) = true
            override fun sendControl(bytes: ByteArray) = false
            override fun sendHover(bytes: ByteArray) = false
            override fun close() = Unit
        }
        lateinit var socket: SignallingListener
        var starts = 0
        val session = DeviceStreamSession(RuntimeEnvironment.getApplication(),
            { StreamCredentials("wss://stream.test/a", "test") }, backgroundScope,
            StreamConfig(publishCapabilities = DevicePublishCapabilities(true, false, "direct")),
            SignallingTransportFactory { _, listener ->
                socket = listener
                object : SignallingTransport {
                    override fun send(text: String): Boolean {
                        if (text.contains("publishStart")) starts++
                        return true
                    }
                    override fun close() = Unit
                }
            }, PeerTransportFactory { _, _, listener ->
                listener.onConnectionState(PeerConnectionState.Connected)
                peer
            }, prewarm = {})
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        try {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            session.start()
            runCurrent()
            socket.onOpen()
            runCurrent()
            socket.onMessage("""{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}""")
            runCurrent()
            val start = async { session.startPublishWithPermissions(PublishOptions(true)) {
                permissionCalls++
                permissionOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            } }
            runCurrent()
            assertEquals(0, permissionCalls)
            socket.onMessage("""{"type":"publishState","mediaSessionId":"${session.publishState.value.mediaSessionId}","state":"active"}""")
            runCurrent()
            start.await()
            assertEquals(1, permissionCalls)
            assertTrue(permissionOnMain)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            runCurrent()
            assertEquals(PublishPhase.Stopped, session.publishState.value.phase)
            assertTrue(stops > 0)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            runCurrent()
            assertEquals(1, starts)
            assertEquals(PublishPhase.Stopped, session.publishState.value.phase)
        } finally {
            session.close()
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.*
import ai.mobilerun.stream.protocol.DataChannelSpec
import ai.mobilerun.stream.protocol.DataChannels
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import kotlin.test.*

/** Offline native offer proof: no socket, permission prompt, capture, or remote media. */
class PublishOfferTest {
    @Test fun nativeTracklessOfferReservesSsrcsWithoutCapture() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val listener = object : PeerListener {
            override fun onLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) = Unit
            override fun onConnectionState(state: PeerConnectionState) = Unit
            override fun onChannelState(channel: DataChannelSpec, open: Boolean) = Unit
        }
        val peer = WebRtcPeerTransport(WebRtcRuntime.factory(context), PeerConfiguration(emptyList(), null),
            listOf(DataChannels.control, DataChannels.hover), listener, StreamMedia(true), VideoCodecPreference.Auto,
            context, DevicePublishCapabilities(true, true, "direct"))
        try {
            val sdp = withTimeout(10_000) { peer.createOffer(false) }
            val media = sdp.replace("\r\n", "\n").split("\nm=").drop(1)
            assertTrue(media[0].startsWith("audio "))
            assertTrue(media[1].startsWith("video "))
            assertTrue("a=recvonly" in media[0].lines())
            assertTrue("a=recvonly" in media[1].lines())
            assertTrue(PublishKind.Audio in peer.publishKinds, "Native trackless Opus sender must carry an SSRC")
            val audio = media.last { it.startsWith("audio ") }
            assertTrue("a=sendonly" in audio.lines())
            assertTrue(audio.lines().any { it.startsWith("a=ssrc:") })
            assertTrue(audio.contains("opus/48000/2", true))
            File(context.filesDir, "publish-offer-proof.txt").writeText(
                "trackless audio SSRC: verified\nfirst-offer order: verified\n" +
                    "supported inputs: ${peer.publishKinds.joinToString { it.wireValue }}\nno capture requested\n")
        } finally { peer.close() }
    }
}

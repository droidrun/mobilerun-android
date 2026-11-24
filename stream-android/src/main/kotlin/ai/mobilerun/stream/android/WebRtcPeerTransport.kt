// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.DevicePublishCapabilities
import ai.mobilerun.stream.core.PublishKind
import ai.mobilerun.stream.core.PublishOptions
import ai.mobilerun.stream.core.PublishCapture
import ai.mobilerun.stream.core.PublishPeerTransport
import android.content.Context
import ai.mobilerun.stream.core.StreamStats
import ai.mobilerun.stream.core.PeerConfiguration
import ai.mobilerun.stream.core.PeerConnectionState
import ai.mobilerun.stream.core.PeerListener
import ai.mobilerun.stream.core.PeerTransport
import ai.mobilerun.stream.core.PeerTransportFactory
import ai.mobilerun.stream.protocol.DataChannelSpec
import ai.mobilerun.stream.protocol.DataChannels
import ai.mobilerun.stream.protocol.IceServer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.RTCStatsReport
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class WebRtcPeerTransportFactory(
    private val factory: () -> PeerConnectionFactory,
    private val media: StreamMedia,
    private val codec: VideoCodecPreference,
    private val context: Context,
    private val publishCapabilities: DevicePublishCapabilities?,
) : PeerTransportFactory {
    override fun create(
        configuration: PeerConfiguration,
        channels: List<DataChannelSpec>,
        listener: PeerListener,
    ): WebRtcPeerTransport = WebRtcPeerTransport(factory(), configuration, channels, listener, media, codec, context, publishCapabilities)
}

/** One libwebrtc peer connection: recvonly audio and video, the negotiated data channels. */
internal class WebRtcPeerTransport(
    private val factory: PeerConnectionFactory,
    configuration: PeerConfiguration,
    channelSpecs: List<DataChannelSpec>,
    private val listener: PeerListener,
    private val media: StreamMedia,
    codec: VideoCodecPreference,
    private val context: Context,
    publishCapabilities: DevicePublishCapabilities?,
) : PublishPeerTransport, PeerTransport, PeerConnection.Observer {
    private val rtcConfiguration = PeerConnection.RTCConfiguration(toIceServers(configuration.iceServers)).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        if (configuration.iceTransportPolicy == "relay") iceTransportsType = PeerConnection.IceTransportsType.RELAY
    }
    private val peerConnection: PeerConnection =
        factory.createPeerConnection(rtcConfiguration, this)
            ?: throw IllegalStateException("createPeerConnection returned null")
    private val channels = HashMap<Int, DataChannel>()
    private val upstream = linkedMapOf<PublishKind, RtpTransceiver>()
    private var offeredKinds: Set<PublishKind> = emptySet()
    override val publishKinds: Set<PublishKind> get() = offeredKinds

    override suspend fun capture(options: PublishOptions, onEnded: () -> Unit): PublishCapture =
        AndroidPublishCapture.create(context, factory, upstream, options, onEnded)

    @Volatile private var closed = false
    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var audioTrack: AudioTrack? = null
    /** Called only by the session sampler on its serial context. */
    suspend fun sampleStats(parser: StreamStatsParser): StreamStats? {
        if (closed) return null
        val report = statsReport()
        if (closed || connectionState != PeerConnectionState.Connected) return null
        val types = setOf("inbound-rtp", "transport", "candidate-pair", "local-candidate", "remote-candidate", "codec")
        return parser.parse(report.statsMap.values.filter { it.type in types }.map {
            StatsRecord(it.id, it.type, it.timestampUs, it.members)
        }, media.firstFrameMs)
    }

    /** Native report access for the sampler and instrumentation recording. */
    internal suspend fun statsReport(): RTCStatsReport = suspendCancellableCoroutine { continuation ->
        peerConnection.getStats { report ->
            if (continuation.isActive) continuation.resume(report)
        }
    }

    init {
        WebRtcRuntime.activePeers.incrementAndGet()
        try {
            peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            val video = peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            val capabilities = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            CodecOrder.order(capabilities, { it.mimeType }, codec)?.let { video.setCodecPreferences(it) }
            if (publishCapabilities?.supportsMicrophone == true) {
                val videoCodecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                    .filter { it.mimeType.equals("video/H264", true) && it.parameters["packetization-mode"] == "1" &&
                        supportsPublishH264(it.parameters["profile-level-id"]) }
                    .sortedBy { if (it.parameters["profile-level-id"]?.startsWith("42") == true) 0 else 1 }
                if (publishCapabilities.cameraInjection && videoCodecs.isNotEmpty()) {
                    upstream[PublishKind.Video] = peerConnection.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
                    ).also { it.setCodecPreferences(videoCodecs) }
                }
                upstream[PublishKind.Audio] = peerConnection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
                ).also { transceiver ->
                    val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs
                        .filter { it.mimeType.equals("audio/opus", true) && it.clockRate == 48000 && it.numChannels == 2 }
                    if (opus.isNotEmpty()) transceiver.setCodecPreferences(opus)
                }
            }
            for (spec in channelSpecs) createChannel(spec)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun createChannel(spec: DataChannelSpec) {
        val init = DataChannel.Init().apply {
            ordered = spec.ordered
            negotiated = true
            id = spec.id
            spec.maxRetransmits?.let { maxRetransmits = it }
        }
        val channel = peerConnection.createDataChannel(spec.label, init)
            ?: throw IllegalStateException("createDataChannel returned null")
        channels[spec.id] = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onMessage(buffer: DataChannel.Buffer) = Unit

            override fun onStateChange() {
                if (!closed) listener.onChannelState(spec, channel.state() == DataChannel.State.OPEN)
            }
        })
    }

    override val connectionState: PeerConnectionState
        get() = if (closed) PeerConnectionState.Closed else peerConnection.connectionState().toCore()

    override suspend fun createOffer(iceRestart: Boolean): String = suspendCancellableCoroutine { continuation ->
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peerConnection.createOffer(
            object : SdpCallback(continuation::resumeWithException) {
                override fun onCreateSuccess(description: SessionDescription) {
                    peerConnection.setLocalDescription(
                        object : SdpCallback(continuation::resumeWithException) {
                            override fun onSetSuccess() {
                                if (!iceRestart) offeredKinds = validPublishReservations(description.description,
                                    upstream.mapValues { it.value.mid })
                                continuation.resume(description.description)
                            }
                        },
                        description,
                    )
                }
            },
            constraints,
        )
    }

    override suspend fun setRemoteAnswer(sdp: String) = suspendCancellableCoroutine { continuation ->
        peerConnection.setRemoteDescription(
            object : SdpCallback(continuation::resumeWithException) {
                override fun onSetSuccess() = continuation.resume(Unit)
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    override suspend fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        // The browser rejects a candidate with neither a media id nor a line index; so do we.
        require(sdpMid != null || sdpMLineIndex != null) { "candidate without sdpMid and sdpMLineIndex" }
        check(peerConnection.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate))) {
            "candidate rejected"
        }
    }

    override fun setIceServers(iceServers: List<IceServer>): Boolean {
        rtcConfiguration.iceServers = toIceServers(iceServers)
        return peerConnection.setConfiguration(rtcConfiguration)
    }

    override fun sendControl(bytes: ByteArray): Boolean = send(DataChannels.control.id, bytes)

    override fun sendHover(bytes: ByteArray): Boolean = send(DataChannels.hover.id, bytes)

    private fun send(id: Int, bytes: ByteArray): Boolean {
        if (closed) return false
        val channel = channels[id] ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    override fun close() {
        if (closed) return
        closed = true
        videoTrack?.let(media::detachVideo)
        audioTrack?.let(media::detachAudio)
        for (channel in channels.values) {
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
        }
        runCatching { peerConnection.close() }
        // Native disposal waits a moment so that a renderer can still detach from the track.
        WebRtcRuntime.disposeLater {
            try {
                channels.values.forEach { it.dispose() }
                peerConnection.dispose()
            } finally {
                WebRtcRuntime.activePeers.decrementAndGet()
            }
        }
    }

    // ---- PeerConnection.Observer (libwebrtc signalling thread) ----

    override fun onIceCandidate(candidate: IceCandidate) {
        if (!closed) listener.onLocalCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        if (!closed) listener.onConnectionState(newState.toCore())
    }

    override fun onTrack(transceiver: RtpTransceiver) {
        if (closed) return
        when (val track = transceiver.receiver.track()) {
            is VideoTrack -> {
                videoTrack = track
                media.attachVideo(track)
            }
            is AudioTrack -> {
                audioTrack = track
                media.attachAudio(track)
            }
        }
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

    override fun onAddStream(stream: MediaStream) = Unit

    override fun onRemoveStream(stream: MediaStream) = Unit

    override fun onDataChannel(dataChannel: DataChannel) = Unit

    override fun onRenegotiationNeeded() = Unit

    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit

    /** An SDP observer that turns failures into the exception of the waiting coroutine. */
    private abstract class SdpCallback(private val fail: (Throwable) -> Unit) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) = fail(IllegalStateException("SDP create failed: $error"))

        override fun onSetFailure(error: String?) = fail(IllegalStateException("SDP set failed: $error"))
    }

    private companion object {
        fun toIceServers(servers: List<IceServer>): List<PeerConnection.IceServer> =
            servers.filter { it.urls.isNotEmpty() }.map {
                PeerConnection.IceServer.builder(it.urls)
                    .setUsername(it.username ?: "")
                    .setPassword(it.credential ?: "")
                    .createIceServer()
            }

        fun PeerConnection.PeerConnectionState.toCore(): PeerConnectionState = when (this) {
            PeerConnection.PeerConnectionState.NEW -> PeerConnectionState.New
            PeerConnection.PeerConnectionState.CONNECTING -> PeerConnectionState.Connecting
            PeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionState.Connected
            PeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionState.Disconnected
            PeerConnection.PeerConnectionState.FAILED -> PeerConnectionState.Failed
            PeerConnection.PeerConnectionState.CLOSED -> PeerConnectionState.Closed
        }
    }
}

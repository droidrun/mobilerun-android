// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.DataChannelSpec
import ai.mobilerun.stream.protocol.DataChannels
import ai.mobilerun.stream.protocol.IceServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A text frame the engine sent, with the virtual time it was sent at. */
internal class SentFrame(val atMs: Long, val json: JsonObject) {
    val type: String get() = json.getValue("type").jsonPrimitive.content
}

internal class FakeSocket(
    val credentials: StreamCredentials,
    private val listener: SignallingListener,
    private val clock: () -> Long,
) : SignallingTransport {
    val sent = mutableListOf<SentFrame>()
    var isOpen = false
        private set
    var isClosed = false
        private set

    override fun send(text: String): Boolean {
        if (!isOpen || isClosed) return false
        sent += SentFrame(clock(), Json.parseToJsonElement(text).jsonObject)
        return true
    }

    override fun close() {
        isClosed = true
        isOpen = false
    }

    fun framesOf(type: String): List<SentFrame> = sent.filter { it.type == type }

    // Server side.
    fun serverOpens() {
        isOpen = true
        listener.onOpen()
    }

    fun serverSends(text: String) = listener.onMessage(text)

    fun serverCloses(code: Int? = null) {
        isOpen = false
        if (code == null) listener.onClosed() else listener.onClosed(code)
    }

    fun serverRejects(httpStatus: Int?) {
        isOpen = false
        listener.onFailure(httpStatus)
    }
}

internal class FakeSignalling(private val clock: () -> Long) : SignallingTransportFactory {
    val sockets = mutableListOf<FakeSocket>()

    /** Runs while a socket is being opened, before [open] returns. */
    var onOpen: (() -> Unit)? = null

    override fun open(credentials: StreamCredentials, listener: SignallingListener): SignallingTransport =
        FakeSocket(credentials, listener, clock).also {
            sockets += it
            onOpen?.invoke()
        }
}

internal class FakePeer(
    val configuration: PeerConfiguration,
    val channels: List<DataChannelSpec>,
    private val listener: PeerListener,
) : PeerTransport, PublishPeerTransport {
    override val publishKinds = setOf(PublishKind.Audio, PublishKind.Video)
    val publishCapture = FakePublishCapture()
    var captureCount = 0
    override suspend fun capture(options: PublishOptions, onEnded: () -> Unit): PublishCapture {
        captureCount++
        return publishCapture
    }
    override var connectionState: PeerConnectionState = PeerConnectionState.New
        private set
    var isClosed = false
        private set

    /** One entry per `createOffer`: whether it was an ICE restart. */
    val offers = mutableListOf<Boolean>()
    val remoteAnswers = mutableListOf<String>()
    val remoteCandidates = mutableListOf<String>()
    val iceServerUpdates = mutableListOf<List<IceServer>>()
    val controlSent = mutableListOf<ByteArray>()
    val hoverSent = mutableListOf<ByteArray>()

    var iceServersAccepted = true
    var offerError: Exception? = null
    var answerError: Exception? = null
    private val open = mutableSetOf<Int>()

    override suspend fun createOffer(iceRestart: Boolean): String {
        offerError?.let { throw it }
        offers += iceRestart
        return "offer-sdp-${offers.size}"
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        answerError?.let { throw it }
        remoteAnswers += sdp
    }

    override suspend fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        remoteCandidates += candidate
    }

    override fun setIceServers(iceServers: List<IceServer>): Boolean {
        iceServerUpdates += iceServers
        return iceServersAccepted
    }

    override fun sendControl(bytes: ByteArray): Boolean =
        (DataChannels.control.id in open).also { if (it) controlSent += bytes }

    override fun sendHover(bytes: ByteArray): Boolean =
        (DataChannels.hover.id in open).also { if (it) hoverSent += bytes }

    override fun close() {
        isClosed = true
    }

    // Network side.
    fun emitState(state: PeerConnectionState) {
        connectionState = state
        listener.onConnectionState(state)
    }

    fun emitChannel(channel: DataChannelSpec, isOpen: Boolean) {
        if (isOpen) open += channel.id else open -= channel.id
        listener.onChannelState(channel, isOpen)
    }

    fun emitLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
        listener.onLocalCandidate(candidate, sdpMid, sdpMLineIndex)
}

internal class FakePeers : PeerTransportFactory {
    val peers = mutableListOf<FakePeer>()
    var createError: Exception? = null

    /** Runs while a peer connection is being created, before [create] returns. */
    var onCreate: (() -> Unit)? = null

    override fun create(
        configuration: PeerConfiguration,
        channels: List<DataChannelSpec>,
        listener: PeerListener,
    ): PeerTransport {
        createError?.let { throw it }
        return FakePeer(configuration, channels, listener).also {
            peers += it
            onCreate?.invoke()
        }
    }
}

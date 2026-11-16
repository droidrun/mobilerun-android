// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.InternalStreamApi
import ai.mobilerun.stream.protocol.DataChannelSpec
import ai.mobilerun.stream.protocol.IceServer

/**
 * The signalling WebSocket of one connection attempt. Implementations deliver their events to the
 * [SignallingListener] they were opened with, on any thread, and never after [close].
 */
@InternalStreamApi
public interface SignallingTransport {
    /** Sends one text frame. Returns `false` if the socket is not open (the frame is dropped). */
    public fun send(text: String): Boolean

    /** Closes the socket and stops all events. Idempotent. */
    public fun close()
}

/** Events of a [SignallingTransport]. May be called from any thread. */
@InternalStreamApi
public interface SignallingListener {
    /** The socket is open. */
    public fun onOpen()

    /** A text frame arrived. */
    public fun onMessage(text: String)

    /** The socket closed, normally or abruptly. */
    public fun onClosed()

    /**
     * The socket failed.
     *
     * @param httpStatus the HTTP status of a rejected upgrade request (`401` for a rejected
     *   token), or `null` if there was no HTTP response.
     */
    public fun onFailure(httpStatus: Int?)
}

/** Opens signalling sockets. */
@InternalStreamApi
public fun interface SignallingTransportFactory {
    /**
     * Starts opening a socket to [credentials]; the result is reported through [listener].
     * Throws if the request cannot even be built.
     */
    public fun open(credentials: StreamCredentials, listener: SignallingListener): SignallingTransport
}

/** State of a peer connection (same values as the WebRTC `connectionState`). */
@InternalStreamApi
public enum class PeerConnectionState { New, Connecting, Connected, Disconnected, Failed, Closed }

/**
 * Peer connection settings from the initial `rtcConfiguration` reply.
 *
 * @property iceServers the ICE servers.
 * @property iceTransportPolicy `all` or `relay`, or `null` for the default.
 */
@InternalStreamApi
public class PeerConfiguration(public val iceServers: List<IceServer>, public val iceTransportPolicy: String?)

/** Events of a [PeerTransport]. May be called from any thread. */
@InternalStreamApi
public interface PeerListener {
    /** A local ICE candidate was gathered. The end-of-candidates event is not reported. */
    public fun onLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    /** The peer connection state changed. */
    public fun onConnectionState(state: PeerConnectionState)

    /** A negotiated data channel opened or closed. */
    public fun onChannelState(channel: DataChannelSpec, open: Boolean)
}

/**
 * One peer connection with recvonly audio and video and the negotiated data channels. Created by a
 * [PeerTransportFactory]; all its events go to the [PeerListener] it was created with.
 */
@InternalStreamApi
public interface PeerTransport {
    /** The current connection state. */
    public val connectionState: PeerConnectionState

    /** Creates an offer (with ICE restart if [iceRestart]), sets it as local description and returns its SDP. */
    public suspend fun createOffer(iceRestart: Boolean): String

    /** Sets the remote description from the answer [sdp]. Throws if it is rejected. */
    public suspend fun setRemoteAnswer(sdp: String)

    /** Adds a remote ICE candidate. Throws if it is rejected. */
    public suspend fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    /** Replaces the ICE servers of the live connection. Returns `false` if the configuration was rejected. */
    public fun setIceServers(iceServers: List<IceServer>): Boolean

    /** Sends on the control channel; `false` if the channel is not open. */
    public fun sendControl(bytes: ByteArray): Boolean

    /** Sends on the hover channel; `false` if the channel is not open. Never falls back to control. */
    public fun sendHover(bytes: ByteArray): Boolean

    /** Releases the connection and stops all events. Idempotent. */
    public fun close()
}

/** Creates peer connections. */
@InternalStreamApi
public fun interface PeerTransportFactory {
    /**
     * Creates a peer connection from [configuration] with recvonly audio and video transceivers and
     * the negotiated data [channels]. Throws if that is impossible.
     */
    public fun create(
        configuration: PeerConfiguration,
        channels: List<DataChannelSpec>,
        listener: PeerListener,
    ): PeerTransport
}

/** Receives diagnostic lines. They never contain tokens, URLs, SDP or ICE credentials. */
@InternalStreamApi
public fun interface StreamLogger {
    /** Logs one line. */
    public fun log(message: String)
}

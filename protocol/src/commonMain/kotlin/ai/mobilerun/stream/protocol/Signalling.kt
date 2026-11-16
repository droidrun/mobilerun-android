// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

internal val SignallingJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

/**
 * A text frame the client sends over the signalling WebSocket. The JSON `type` field is the discriminator.
 *
 * Messages are data classes. Fields are never added to an existing constructor; a new field gets a new type.
 */
@Serializable
@InternalStreamApi
public sealed interface ClientMessage {
    /** The frame as the JSON text to put on the WebSocket. */
    public fun toJson(): String = SignallingJson.encodeToString(ClientMessage.serializer(), this)

    /**
     * First message after the WebSocket opens; asks the server for the ICE configuration.
     *
     * @property sessionId id of this stream session.
     */
    @Serializable
    @SerialName("requestRtcConfiguration")
    @InternalStreamApi
    public data class RequestRtcConfiguration(val sessionId: String) : ClientMessage {
        /** Opt-in to unsolicited mid-session credential refreshes. Always `true`. */
        @EncodeDefault
        public val supportsLiveRtcRefresh: Boolean = true
    }

    /**
     * SDP offer: sent once after the peer connection is set up and again for every ICE restart.
     *
     * @property sdp the offer SDP.
     * @property sessionId id of this stream session.
     */
    @Serializable
    @SerialName("offer")
    @InternalStreamApi
    public data class Offer(val sdp: String, val sessionId: String) : ClientMessage

    /**
     * A locally gathered ICE candidate, sent as soon as it is gathered. The end-of-candidates
     * event is not sent.
     *
     * @property candidate SDP candidate line.
     * @property sdpMid media stream id, may be `null`; always written, as an explicit `null`.
     * @property sdpMLineIndex media line index, may be `null`; always written, as an explicit `null`.
     * @property sessionId id of this stream session.
     */
    @Serializable
    @SerialName("candidate")
    @InternalStreamApi
    public data class Candidate(
        val candidate: String,
        @EncodeDefault val sdpMid: String? = null,
        @EncodeDefault val sdpMLineIndex: Int? = null,
        val sessionId: String,
    ) : ClientMessage

    /**
     * Sent every [SignallingTiming.KEEP_ALIVE_INTERVAL_MS] while the host is visible and the socket is open.
     *
     * @property sessionId id of this stream session.
     */
    @Serializable
    @SerialName("keepAlive")
    @InternalStreamApi
    public data class KeepAlive(val sessionId: String) : ClientMessage

    /**
     * Asks the encoder for a fresh frame. Sent [SignallingTiming.REQUEST_FRAME_COUNT] times,
     * [SignallingTiming.REQUEST_FRAME_INTERVAL_MS] apart, starting when the control channel opens.
     *
     * @property sessionId id of this stream session.
     */
    @Serializable
    @SerialName("requestFrame")
    @InternalStreamApi
    public data class RequestFrame(val sessionId: String) : ClientMessage

    /**
     * Optional; sent once when the control channel opens if the embedding app supplied a URL to open.
     *
     * @property url the URL to open on the device. Messages are only built with [create], which
     *   applies the percent-decoding the reference client applies.
     * @property sessionId id of this stream session.
     */
    @Serializable
    @SerialName("openUrl")
    @ConsistentCopyVisibility
    @InternalStreamApi
    public data class OpenUrl internal constructor(val url: String, val sessionId: String) : ClientMessage {
        /** Factory for [OpenUrl]. */
        @InternalStreamApi
        public companion object {
            /**
             * Builds the message from the URL the app supplied: the URL is percent-decoded
             * (`decodeURIComponent`); if it is not valid percent-encoding it is used unchanged.
             */
            public fun create(suppliedUrl: String, sessionId: String): OpenUrl =
                OpenUrl(decodeUriComponent(suppliedUrl) ?: suppliedUrl, sessionId)
        }
    }

    /**
     * Requests a screenshot. The reply ([ServerMessage.Screenshot] or [ServerMessage.ScreenshotError])
     * carries the same [id]. There is no session id in this message. The client gives up after
     * [SignallingTiming.SCREENSHOT_TIMEOUT_MS].
     *
     * @property id client-chosen correlation id.
     */
    @Serializable
    @SerialName("screenshot")
    @InternalStreamApi
    public data class ScreenshotRequest(val id: String) : ClientMessage
}

/**
 * A text frame received over the signalling WebSocket. The JSON `type` field is the discriminator.
 *
 * Messages are plain classes created by [parse] only, so that fields can be added without breaking
 * callers (no `copy`, no destructuring, no generated equality).
 */
@Serializable
@InternalStreamApi
public sealed interface ServerMessage {
    /**
     * SDP answer to the latest offer (initial or ICE restart).
     *
     * @property sdp the answer SDP.
     */
    @Serializable
    @SerialName("answer")
    @InternalStreamApi
    public class Answer internal constructor(public val sdp: String) : ServerMessage

    /**
     * A remote ICE candidate. Buffer it until the remote description is set, then apply it in arrival order.
     *
     * @property candidate SDP candidate line.
     * @property sdpMid media stream id, may be `null`.
     * @property sdpMLineIndex media line index, may be `null`.
     */
    @Serializable
    @SerialName("candidate")
    @InternalStreamApi
    public class Candidate internal constructor(
        public val candidate: String,
        @EncodeDefault public val sdpMid: String? = null,
        @EncodeDefault public val sdpMLineIndex: Int? = null,
    ) : ServerMessage

    /**
     * ICE configuration. Which of the two cases it is depends on the connection phase, not on its
     * content: the first one after `requestRtcConfiguration` is the initial reply; once the peer
     * connection exists any further one is a mid-session credential refresh (see [SignallingPhase]).
     *
     * @property configuration the whole `rtcConfiguration` object as sent (the initial reply hands
     *   all of it to the peer connection), or `null` if absent.
     */
    @Serializable
    @SerialName("rtcConfiguration")
    @InternalStreamApi
    public class RtcConfiguration internal constructor(
        @SerialName("rtcConfiguration") public val configuration: JsonObject? = null,
    ) : ServerMessage {
        /** The `iceTransportPolicy` of [configuration] (`all` or `relay`), or `null` if absent. */
        public val iceTransportPolicy: String?
            get() = (configuration?.get("iceTransportPolicy") as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * The ICE servers in [configuration], or `null` if there are none or they are malformed.
         * A refresh without ICE servers must be ignored; an empty list is a valid refresh.
         */
        public val iceServers: List<IceServer>?
            get() {
                val element = configuration?.get("iceServers") as? JsonArray ?: return null
                return try {
                    SignallingJson.decodeFromJsonElement(ListSerializer(IceServer.serializer()), element)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
    }

    /**
     * Successful screenshot reply.
     *
     * @property id id of the [ClientMessage.ScreenshotRequest] it answers; ignore it if none is pending.
     * @property dataUri the image as a data URI, for example `data:image/png;base64,...`.
     */
    @Serializable
    @SerialName("screenshot")
    @InternalStreamApi
    public class Screenshot internal constructor(
        public val id: String,
        public val dataUri: String,
    ) : ServerMessage

    /**
     * Failed screenshot reply.
     *
     * @property id id of the [ClientMessage.ScreenshotRequest] it answers; ignore it if none is pending.
     * @property message human-readable failure reason.
     */
    @Serializable
    @SerialName("screenshotError")
    @InternalStreamApi
    public class ScreenshotError internal constructor(
        public val id: String,
        public val message: String,
    ) : ServerMessage

    /** Parser entry point. */
    @InternalStreamApi
    public companion object {
        /**
         * Parses one text frame received in [phase].
         *
         * Rules (shared with the other SDKs through the conformance fixtures):
         * - While [SignallingPhase.AwaitingRtcConfiguration], text that is not valid JSON is
         *   [ServerFrame.StartupFailure]; valid messages of any type other than `rtcConfiguration`
         *   are [ServerFrame.Ignored].
         * - Once [SignallingPhase.Established], text that is not valid JSON, messages of an unknown
         *   type and messages that miss a required field are [ServerFrame.Ignored].
         */
        public fun parse(text: String, phase: SignallingPhase): ServerFrame = parseServerFrame(text, phase)
    }
}

internal fun ServerMessage.toJson(): String = SignallingJson.encodeToString(ServerMessage.serializer(), this)

/** An ICE server entry of an [ServerMessage.RtcConfiguration]. */
@Serializable
@InternalStreamApi
public class IceServer internal constructor(
    /** One or more server URLs; a single JSON string is read as a one-element list. */
    @Serializable(with = UrlListSerializer::class) public val urls: List<String>,
    /** TURN username, if any. */
    public val username: String? = null,
    /** TURN credential, if any. */
    public val credential: String? = null,
)

/** Where the connection is, which decides how a received frame is treated. */
@InternalStreamApi
public enum class SignallingPhase {
    /** After `requestRtcConfiguration` was sent, before the general message handler is attached. */
    AwaitingRtcConfiguration,

    /** Peer connection and the general message handler exist; everything else is routed from here. */
    Established,
}

/** Result of parsing one received text frame. */
@InternalStreamApi
public sealed interface ServerFrame {
    /**
     * A recognised message.
     *
     * @property message the parsed message.
     */
    @InternalStreamApi
    public class Message(public val message: ServerMessage) : ServerFrame

    /** Valid but irrelevant or unknown; drop it silently. */
    @InternalStreamApi
    public data object Ignored : ServerFrame

    /** The frame invalidates the attempt while awaiting the ICE configuration; report a failure. */
    @InternalStreamApi
    public data object StartupFailure : ServerFrame
}

internal object UrlListSerializer :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** Why a connection attempt of a device stream failed (stream client contract v1). */
public enum class StreamErrorReason {
    /** The token provider threw, returned a blank key, or did not answer within 10 s. */
    CredentialsUnavailable,

    /** The server answered the WebSocket upgrade with HTTP 401: the key was rejected, used or expired. */
    TokenRejected,

    /** The WebSocket ended before it opened for any other reason: wrong device, refused origin, no route. */
    ConnectionRefused,

    /** A session that was open dropped. Includes the server's session cap. */
    Disconnected,

    /** Reserved: the server said the device is offline. The signalling protocol has no such message yet. */
    DeviceUnavailable,

    /** The server refused local media publication. */
    PublishNotAllowed,
    /** Device, negotiation or carrier cannot publish. */
    PublishUnsupported,
    /** The host has not granted capture permission. */
    PermissionDenied,
    /** Another publisher holds the device lease. */
    PublishBusy,
    /** Capture, attachment or device injection failed. */
    PublishFailed,
    /** Accepted publication never became active. */
    PublishTimeout,
    /** Explicit cancellation of a pending start (never emitted on errors). */
    PublishCancelled,

    /** None of the above. */
    Unknown,
}

/**
 * One failed connection attempt, reported on the `errors` flow of a session.
 *
 * [message] is a fixed diagnostic string for logs; it never contains a key or URL. Show your own
 * text to users. Equality is hand-written and there is no data class so fields can be added.
 *
 * @property reason what went wrong.
 * @property attempt number of failures in the current recovery cycle, starting at 1.
 * @property final `true` when the SDK stops retrying; `retry()` or `handleWake()` starts a new cycle.
 */
public class StreamError internal constructor(
    public val reason: StreamErrorReason,
    public val message: String,
    public val attempt: Int,
    public val final: Boolean,
    public val operation: String? = null,
    public val mediaSessionId: String? = null,
    public val detail: String? = null,
    public val serverReason: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is StreamError && other.reason == reason && other.message == message &&
            other.attempt == attempt && other.final == final && other.operation == operation &&
            other.mediaSessionId == mediaSessionId && other.detail == detail && other.serverReason == serverReason

    override fun hashCode(): Int {
        var result = reason.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + attempt
        result = 31 * result + final.hashCode()
        result = 31 * result + (operation?.hashCode() ?: 0)
        result = 31 * result + (mediaSessionId?.hashCode() ?: 0)
        result = 31 * result + (detail?.hashCode() ?: 0)
        result = 31 * result + (serverReason?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "StreamError(reason=$reason, attempt=$attempt, final=$final)"
}

internal fun StreamErrorReason.diagnostic(): String = when (this) {
    StreamErrorReason.CredentialsUnavailable -> "the token provider failed or timed out"
    StreamErrorReason.TokenRejected -> "the server rejected the stream key"
    StreamErrorReason.ConnectionRefused -> "the connection closed before it opened"
    StreamErrorReason.Disconnected -> "the open connection dropped"
    StreamErrorReason.DeviceUnavailable -> "the device is not available"
    StreamErrorReason.PublishNotAllowed -> "publishing is not allowed"
    StreamErrorReason.PublishUnsupported -> "publishing is not supported"
    StreamErrorReason.PermissionDenied -> "microphone or camera permission is required"
    StreamErrorReason.PublishBusy -> "another publisher is active"
    StreamErrorReason.PublishFailed -> "publishing failed"
    StreamErrorReason.PublishTimeout -> "publishing did not become active"
    StreamErrorReason.PublishCancelled -> "publishing was cancelled"
    StreamErrorReason.Unknown -> "the connection attempt failed"
}

/** Stable stream-client contract reason spelling. */
public val StreamErrorReason.wireValue: String
    get() = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

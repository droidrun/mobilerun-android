// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** What the user of a stream should see: the connection status of one device stream. */
public sealed interface StreamStatus {
    /** A fresh attempt is negotiating, or the session waits for credentials. */
    public data object Connecting : StreamStatus

    /** Media is flowing. */
    public data object Connected : StreamStatus

    /**
     * The connection dropped or an attempt failed; the session waits out the backoff or retries.
     *
     * @property attempt 1-based number of the upcoming (or in-flight) reconnect attempt.
     */
    public class Reconnecting(public val attempt: Int) : StreamStatus {
        override fun equals(other: Any?): Boolean = other is Reconnecting && other.attempt == attempt

        override fun hashCode(): Int = attempt

        override fun toString(): String = "Reconnecting(attempt=$attempt)"
    }

    /** The retry budget is spent; only `retry()` starts a new cycle. */
    public data object Unavailable : StreamStatus
}

internal fun StreamReconnectSnapshot.toStatus(): StreamStatus = when (status) {
    StreamReconnectStatus.Connecting -> StreamStatus.Connecting
    StreamReconnectStatus.Connected -> StreamStatus.Connected
    StreamReconnectStatus.Reconnecting -> StreamStatus.Reconnecting(attempt)
    StreamReconnectStatus.Unavailable -> StreamStatus.Unavailable
}

/** A session operation needs a live connection and there is none. */
public class StreamNotConnectedException(message: String = "the stream is not connected") :
    IllegalStateException(message)

/** The connection that a request was waiting on went away before it was answered. */
public class StreamDisconnectedException : IllegalStateException("the stream connection was closed")

/** The device reported that it could not take the screenshot. */
public class ScreenshotFailedException(message: String) : RuntimeException(message)

/** No screenshot reply arrived within the timeout. */
public class ScreenshotTimeoutException : RuntimeException("the screenshot timed out")

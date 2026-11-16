// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/**
 * What the user of a stream should see: the connection status of one device stream.
 *
 * Equality is hand-written and there are no data classes: public session types must be able to gain
 * fields without breaking callers.
 */
public sealed interface StreamStatus {
    /** A fresh attempt is negotiating, or the session waits for credentials. */
    public data object Connecting : StreamStatus

    /** Media is flowing. */
    public data object Connected : StreamStatus

    /**
     * The connection dropped or an attempt failed; the session waits out the backoff or retries.
     *
     * @property attempt 1-based number of the upcoming (or in-flight) reconnect attempt; 0 right after
     *   `retry()` or `handleWake()` started a fresh cycle.
     */
    public class Reconnecting(public val attempt: Int) : StreamStatus {
        override fun equals(other: Any?): Boolean = other is Reconnecting && other.attempt == attempt

        override fun hashCode(): Int = attempt

        override fun toString(): String = "Reconnecting(attempt=$attempt)"
    }

    /** The retry budget is spent; `retry()` or `handleWake()` starts a new cycle. */
    public data object Unavailable : StreamStatus
}

internal fun StreamReconnectSnapshot.toStatus(): StreamStatus = when (status) {
    StreamReconnectStatus.Connecting -> StreamStatus.Connecting
    StreamReconnectStatus.Connected -> StreamStatus.Connected
    StreamReconnectStatus.Reconnecting -> StreamStatus.Reconnecting(attempt)
    StreamReconnectStatus.Unavailable -> StreamStatus.Unavailable
}

/** A failure reported by a stream operation. */
public sealed class StreamException protected constructor(message: String) : RuntimeException(message)

/** A session operation needs a live connection and there is none. */
public class StreamNotConnectedException internal constructor() : StreamException("the stream is not connected")

/** The connection ended before a request was answered. */
public class StreamDisconnectedException internal constructor() : StreamException("the stream connection was closed")

/** The device reported that it could not take the screenshot. */
public class ScreenshotFailedException internal constructor(message: String) : StreamException(message)

/** No screenshot reply arrived within the timeout. */
public class ScreenshotTimeoutException internal constructor() : StreamException("the screenshot timed out")

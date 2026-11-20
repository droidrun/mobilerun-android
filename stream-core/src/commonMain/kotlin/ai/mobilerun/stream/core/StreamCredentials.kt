// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** Supplies a fresh, single-use key before each new signalling socket. */
public typealias StreamTokenProvider = suspend () -> String

/**
 * Where and how to open the signalling WebSocket of one device stream.
 *
 * [toString] never contains the token, so instances are safe to log.
 *
 * @property url the WebSocket URL (`wss://...`) of the stream.
 * @property token legacy static token (deprecated path); prefer [tokenProvider]. May be empty.
 * @property tokenProvider fresh key callback, bound to this target. Takes precedence over static or
 *   embedded tokens, with a 10 s deadline and no fallback. Callback identity is excluded from equality.
 */
public class StreamCredentials(
    public val url: String,
    public val token: String = "",
    public val tokenProvider: StreamTokenProvider? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is StreamCredentials && other.url == url && other.token == token

    override fun hashCode(): Int = 31 * url.hashCode() + token.hashCode()

    override fun toString(): String = "StreamCredentials(url=<redacted>, token=<redacted>)"
}

/** Supplies the stream credentials of a device. */
public fun interface StreamCredentialsProvider {
    /**
     * Returns the current credentials, or `null` while the device is not ready to be streamed.
     *
     * Called when the session starts, whenever a reconnect cycle begins (the previous URL or token
     * may have gone stale), and when the server rejects the token. It may suspend, for example for
     * a network request. An exception is treated like `null`.
     */
    public suspend fun credentials(): StreamCredentials?
}

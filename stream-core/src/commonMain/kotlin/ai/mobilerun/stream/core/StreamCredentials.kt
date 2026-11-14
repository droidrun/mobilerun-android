// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/**
 * Where and how to open the signalling WebSocket of one device stream.
 *
 * [toString] never contains the token, so instances are safe to log.
 *
 * @property url the WebSocket URL (`wss://...`) of the stream.
 * @property token the stream token; sent as the `token` query parameter. May be empty if the URL
 *   already carries its own authorisation.
 */
public class StreamCredentials(public val url: String, public val token: String) {
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

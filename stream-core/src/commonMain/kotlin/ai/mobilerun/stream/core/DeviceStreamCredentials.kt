// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlinx.coroutines.delay

/**
 * Backend-only credentials fetch with React's three-second readiness polling. Pass [provider] into
 * the session. Each call fetches afresh (including recovery/heal); null means not ready. Cancelling
 * the session cancels polling. No API key belongs in the app or in this helper.
 */
public class DeviceStreamCredentials(
    private val fetchDevice: suspend () -> StreamCredentials?,
    private val pollIntervalMs: Long = 3_000,
) {
    init { require(pollIntervalMs > 0) { "pollIntervalMs must be positive" } }

    /** Poll until the backend supplies credentials. Fetch failures propagate to the session. */
    public val provider: StreamCredentialsProvider = StreamCredentialsProvider {
        var result = fetchDevice()
        while (result == null) {
            delay(pollIntervalMs)
            result = fetchDevice()
        }
        result
    }
}

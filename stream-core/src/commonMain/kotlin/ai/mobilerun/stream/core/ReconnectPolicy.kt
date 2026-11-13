// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/** Constants and the backoff function of the reconnect policy shared by every Mobilerun stream SDK. */
public object ReconnectPolicy {
    /** Consecutive failed attempts after which the stream is declared unavailable and retries stop. */
    public const val MAX_ATTEMPTS: Int = 6

    /** How long the signalling WebSocket may take to open. */
    public const val WS_OPEN_TIMEOUT_MS: Long = 15_000

    /** How long the ICE configuration reply may take after the socket opened. */
    public const val RTC_CONFIG_TIMEOUT_MS: Long = 15_000

    /**
     * Watchdog for a whole connect attempt. It must exceed [WS_OPEN_TIMEOUT_MS] + [RTC_CONFIG_TIMEOUT_MS]
     * so it never preempts a slow-but-valid handshake the connection layer still considers on budget.
     */
    public const val CONNECT_TIMEOUT_MS: Long = 45_000

    /** Holding a connection this long refills the retry budget and re-arms the credential refresh. */
    public const val STABILITY_MS: Long = 30_000

    /** Backoff base: the delay before the first retry. */
    public const val BASE_DELAY_MS: Long = 1_000

    /** Backoff cap before jitter. */
    public const val MAX_DELAY_MS: Long = 30_000

    /**
     * Delay before reconnect [attempt] (1-based): exponential from [BASE_DELAY_MS] up to
     * [MAX_DELAY_MS], times a jitter factor of `0.75 + random * 0.5` (that is ±25 %), rounded half
     * toward positive infinity.
     *
     * @param attempt consecutive failed attempts so far; values below 1 count as 1.
     * @param random jitter source returning a value in `[0, 1)`; `0.5` means no jitter.
     */
    public fun delayMs(attempt: Int, random: Double): Long {
        val base = min(BASE_DELAY_MS * 2.0.pow(maxOf(1, attempt) - 1), MAX_DELAY_MS.toDouble())
        val jittered = base * (0.75 + random * 0.5)
        val lower = floor(jittered)
        return (if (jittered - lower >= 0.5) lower + 1.0 else lower).toLong()
    }
}

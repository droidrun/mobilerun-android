// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

/** Timings of the signalling exchange, in milliseconds unless noted. */
public object SignallingTiming {
    /** The WebSocket must be open within this time or the attempt fails. */
    public const val WS_OPEN_TIMEOUT_MS: Long = 15_000

    /** The server must answer `requestRtcConfiguration` within this time or the attempt fails. */
    public const val RTC_CONFIG_TIMEOUT_MS: Long = 15_000

    /** Number of `requestFrame` messages sent once the control channel opens. */
    public const val REQUEST_FRAME_COUNT: Int = 12

    /** Delay between two `requestFrame` messages. */
    public const val REQUEST_FRAME_INTERVAL_MS: Long = 125

    /** Interval between `keepAlive` messages while the socket is open. */
    public const val KEEP_ALIVE_INTERVAL_MS: Long = 10_000

    /** After a live credential refresh, non-terminal connection-state dips are suppressed for this long. */
    public const val ICE_RESTART_GRACE_MS: Long = 15_000

    /** A pending screenshot request is rejected after this time without a reply. */
    public const val SCREENSHOT_TIMEOUT_MS: Long = 30_000
}

/**
 * A negotiated WebRTC data channel. Both sides create it with the same [id], so no in-band
 * negotiation takes place.
 *
 * @property label channel label.
 * @property id negotiated channel id.
 * @property ordered whether delivery is ordered.
 * @property maxRetransmits retransmit limit, or `null` for fully reliable delivery.
 */
public class DataChannelSpec internal constructor(
    public val label: String,
    public val id: Int,
    public val ordered: Boolean,
    public val maxRetransmits: Int?,
)

/** The two data channels every session negotiates. */
public object DataChannels {
    /** Reliable and ordered; carries every binary control message except hover. */
    public val control: DataChannelSpec =
        DataChannelSpec("control", id = 1, ordered = true, maxRetransmits = null)

    /** Unordered, no retransmits; carries hover messages only and drops them while not open. */
    public val hover: DataChannelSpec =
        DataChannelSpec("hover", id = 2, ordered = false, maxRetransmits = 0)
}

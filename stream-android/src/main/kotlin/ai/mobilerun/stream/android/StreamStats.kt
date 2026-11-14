// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

/**
 * A one-second inbound-video sample. Missing WebRTC fields are `null`, never synthesized as zero.
 * Rates and per-frame durations use counter deltas since the preceding report; the first report
 * has no rate. A new connection attempt resets all baselines and first-frame timing.
 *
 * This read-only value is created by the SDK, so fields can be added without changing callers.
 */
public class StreamStats internal constructor(
    /** Decoded frames per second (WebRTC's instantaneous value, or a counter delta). */
    public val fps: Double?,
    /** Decoded video width, in pixels. */
    public val width: Int?,
    /** Decoded video height, in pixels. */
    public val height: Int?,
    /** MIME type, for example `video/H264`. */
    public val codec: String?,
    /** Received video payload kilobits per second, excluding transport overhead. */
    public val bitrateKbps: Double?,
    /** Current round-trip time of the selected ICE candidate pair, in milliseconds. */
    public val rttMs: Double?,
    /** Jitter-buffer delay per emitted frame during this sample, in milliseconds. */
    public val jitterBufferDelayMs: Double?,
    /** Cumulative inbound-video lost packets; may decrease after late packets arrive. */
    public val packetsLost: Long?,
    /** Cumulative dropped video frames. */
    public val framesDropped: Long?,
    /** Cumulative freezes, where the receiver reports them. */
    public val freezeCount: Long?,
    /** Decode duration per decoded frame during this sample, in milliseconds. */
    public val decodeTimeMs: Double?,
    /** Selected local candidate: `host`, `srflx`, `prflx` or `relay`, when known. */
    public val localCandidateType: String?,
    /** Selected remote candidate, with the same vocabulary as [localCandidateType]. */
    public val remoteCandidateType: String?,
    /** Selected candidate types as `local/remote`; null unless both are reported. */
    public val candidateType: String?,
    /** Time from this connection attempt's creation to its first decoded video frame. */
    public val timeToFirstFrameMs: Double?,
)

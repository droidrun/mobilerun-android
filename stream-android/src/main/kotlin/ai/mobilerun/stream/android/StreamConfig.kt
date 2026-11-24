// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.DevicePublishCapabilities

/** Which video codecs the receiver asks the server to use. */
public enum class VideoCodecPreference {
    /**
     * The order of the browser client: VP9, H.265, H.264, then the rest; the server picks, and in
     * practice picks H.265. If this device has no H.265 decoder, H.264 only is requested instead.
     */
    Auto,

    /** Only H.264 (plus the retransmission codecs). */
    H264Only,
}

/**
 * Options of a [DeviceStreamSession].
 *
 * There is deliberately no playout-delay option: the browser client asks its jitter buffer for zero
 * playout delay, but the WebRTC Android API (`org.webrtc.RtpReceiver`) has no equivalent setter, so
 * the receive jitter buffer keeps libwebrtc's adaptive default.
 *
 * @property muted whether the device audio is silent. Defaults to `true`; change it later with
 *   [DeviceStreamSession.setMuted].
 * @property videoCodec codec preference, see [VideoCodecPreference].
 */
public class StreamConfig(
    public val muted: Boolean = true,
    public val videoCodec: VideoCodecPreference = VideoCodecPreference.Auto,
    /** Per-device capability snapshot from the integrator's backend; fixed for this connection. */
    public val publishCapabilities: DevicePublishCapabilities? = null,
)

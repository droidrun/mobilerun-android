// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.VideoSize
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * The media of a session, shared by all its connection attempts: the current video track, its
 * size and the audio mute switch. A new attempt replaces the track; closing one clears it.
 */
internal class StreamMedia(initialMuted: Boolean) {
    private val track = MutableStateFlow<VideoTrack?>(null)
    private val size = MutableStateFlow<VideoSize?>(null)

    val videoTrack: StateFlow<VideoTrack?> = track.asStateFlow()
    val videoSize: StateFlow<VideoSize?> = size.asStateFlow()
    @Volatile var firstFrameMs: Double? = null
        private set
    @Volatile private var attemptStartedNs = 0L

    fun beginAttempt() {
        attemptStartedNs = SystemClock.elapsedRealtimeNanos()
        firstFrameMs = null
    }

    @Volatile private var muted = initialMuted
    @Volatile private var audio: AudioTrack? = null

    @Volatile private var sizeSink: VideoSink? = null

    fun attachVideo(video: VideoTrack) {
        track.value?.let(::detachVideo)
        val sink = VideoSink { frame: VideoFrame ->
            if (track.value === video) {
                if (firstFrameMs == null) firstFrameMs =
                    (SystemClock.elapsedRealtimeNanos() - attemptStartedNs) / 1_000_000.0
                size.value = VideoSize(frame.buffer.width, frame.buffer.height, frame.rotation)
            }
        }
        sizeSink = sink
        track.value = video
        video.addSink(sink)
    }

    /** Called before the track is released; a frame already in flight may still arrive. */
    fun detachVideo(video: VideoTrack) {
        if (track.value === video) {
            track.value = null
            size.value = null
            sizeSink?.let { sink -> runCatching { video.removeSink(sink) } }
            sizeSink = null
        }
    }

    fun attachAudio(track: AudioTrack) {
        audio = track
        track.setEnabled(!muted)
    }

    fun detachAudio(track: AudioTrack) {
        if (audio === track) audio = null
    }

    fun setMuted(value: Boolean) {
        muted = value
        runCatching { audio?.setEnabled(!value) }
    }
}

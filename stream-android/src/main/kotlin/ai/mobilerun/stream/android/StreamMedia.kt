// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.VideoSize
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

    @Volatile private var muted = initialMuted
    @Volatile private var audio: AudioTrack? = null

    private val sizeSink = VideoSink { frame: VideoFrame ->
        val next = VideoSize(frame.buffer.width, frame.buffer.height, frame.rotation)
        if (size.value != next) size.value = next
    }

    fun attachVideo(video: VideoTrack) {
        track.value = video
        video.addSink(sizeSink)
    }

    /** Called before the track is released; a frame already in flight may still arrive. */
    fun detachVideo(video: VideoTrack) {
        if (track.value === video) {
            track.value = null
            size.value = null
        }
        runCatching { video.removeSink(sizeSink) }
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

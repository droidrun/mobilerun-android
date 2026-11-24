// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.PublishCapture
import ai.mobilerun.stream.core.PublishException
import ai.mobilerun.stream.core.PublishFailure
import ai.mobilerun.stream.core.PublishKind
import ai.mobilerun.stream.core.PublishOptions
import ai.mobilerun.stream.core.StreamErrorReason
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/** One owned capture; no Activity, permission prompt, foreground service or downstream media. */
internal class AndroidPublishCapture private constructor(
    private val context: Context,
    private val senders: Map<PublishKind, RtpTransceiver>,
    private val onEnded: () -> Unit,
) : PublishCapture {
    private val tracks = linkedMapOf<PublishKind, MediaStreamTrack>()
    private val captureId = java.util.UUID.randomUUID().toString()
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var camera: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private val monitor = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stopped = false

    override suspend fun attach(kind: PublishKind) {
        check(!stopped)
        val transceiver = senders[kind]
        val direction = transceiver?.currentDirection
        if (transceiver == null || direction !in setOf(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, RtpTransceiver.RtpTransceiverDirection.SEND_RECV)) {
            throw PublishException(PublishFailure(StreamErrorReason.PublishUnsupported,
                "The negotiated sender is unavailable", "negotiated_sender"))
        }
        check(transceiver.sender.setTrack(tracks.getValue(kind), false)) { "sender attachment failed" }
        if (kind == PublishKind.Video) runCatching {
            val parameters = transceiver.sender.parameters
            parameters.encodings.forEach { it.maxBitrateBps = 1_000_000 }
            transceiver.sender.parameters = parameters
        }
    }

    override fun detach() {
        tracks.forEach { (kind, track) ->
            runCatching {
                val sender = senders[kind]?.sender
                if (sender?.track()?.id() == track.id()) sender.setTrack(null, false)
            }
        }
    }

    @Synchronized override fun stop() {
        if (stopped) return
        stopped = true
        monitor.cancel()
        WebRtcRuntime.captureErrors.remove(onEnded)
        runCatching { camera?.stopCapture() }
        runCatching { camera?.dispose() }
        runCatching { texture?.dispose() }
        tracks.values.forEach { runCatching { it.setEnabled(false) } }
        // Detach before disposing track wrappers; a late detach is idempotent by track identity.
        detach()
        tracks.values.forEach { runCatching { it.dispose() } }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
    }

    private fun initialize(factory: PeerConnectionFactory, options: PublishOptions) {
        try {
            checkPermissions(context, options)
            val constraints = MediaConstraints().apply {
                optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = factory.createAudioSource(constraints)
            tracks[PublishKind.Audio] = factory.createAudioTrack("mr-publish-audio-$captureId", audioSource)
            WebRtcRuntime.captureErrors.add(onEnded)
            if (options.video) {
                val enumerator = Camera2Enumerator(context)
                val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull() ?: error("No camera input")
                val events = object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(errorDescription: String) = ended()
                    override fun onCameraDisconnected() = ended()
                    override fun onCameraFreezed(errorDescription: String) = ended()
                    override fun onCameraOpening(cameraName: String) = Unit
                    override fun onFirstFrameAvailable() = Unit
                    override fun onCameraClosed() { if (!stopped) ended() }
                }
                camera = enumerator.createCapturer(name, events) ?: error("Camera unavailable")
                videoSource = factory.createVideoSource(false)
                texture = SurfaceTextureHelper.create("mr-publish-camera", WebRtcRuntime.eglBase.eglBaseContext)
                    ?: error("Camera texture unavailable")
                camera!!.initialize(texture, context, videoSource!!.capturerObserver)
                tracks[PublishKind.Video] = factory.createVideoTrack("mr-publish-video-$captureId", videoSource)
                camera!!.startCapture(1280, 720, 24)
            }
            monitor.launch {
                while (true) {
                    delay(500)
                    if (inputsUnavailable(options)) {
                        ended()
                        return@launch
                    }
                }
            }
        } catch (e: Exception) {
            stop()
            if (e is SecurityException) throw PublishException(PublishFailure(StreamErrorReason.PermissionDenied,
                "Capture permission was refused", "capture_permission"))
            throw e
        }
    }

    @Synchronized private fun inputsUnavailable(options: PublishOptions): Boolean =
        !stopped && (!hasPermissions(context, options) ||
            tracks.values.any { it.state() == MediaStreamTrack.State.ENDED })

    private fun ended() { if (!stopped) onEnded() }

    companion object {
        fun create(context: Context, factory: PeerConnectionFactory, senders: Map<PublishKind, RtpTransceiver>,
            options: PublishOptions, onEnded: () -> Unit): AndroidPublishCapture =
            AndroidPublishCapture(context, senders, onEnded).also { it.initialize(factory, options) }
    }
}

internal fun hasPermissions(context: Context, options: PublishOptions): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        (!options.video || context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)

internal fun checkPermissions(context: Context, options: PublishOptions) {
    if (!hasPermissions(context, options)) throw PublishException(PublishFailure(
        StreamErrorReason.PermissionDenied, "Grant microphone and requested camera permission before starting",
        "capture_permission"))
}

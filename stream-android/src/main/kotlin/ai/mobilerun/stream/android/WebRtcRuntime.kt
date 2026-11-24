// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import android.content.Context
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-wide WebRTC state: one [PeerConnectionFactory] and one [EglBase], shared by every
 * session. libwebrtc initialises global native state once per process and the factory owns the
 * worker threads and the codec factories, so creating one per session would be slow and wasteful.
 * They live until the process ends and are deliberately never disposed; a session's `close()`
 * releases only its own peer connection, data channels and tracks.
 */
internal object WebRtcRuntime {
    /** Internal resource accounting for instrumentation teardown assertions. */
    val activePeers = AtomicInteger()
    /** How long a closed peer connection stays allocated so that renderers can detach from its track. */
    private const val DISPOSE_DELAY_MS = 1_000L

    @Volatile private var factoryInstance: PeerConnectionFactory? = null
    private val lock = Any()
    val captureErrors = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    val encoderFactory by lazy { DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true) }

    /** The EGL context decoders and renderers must share to exchange texture frames. */
    val eglBase: EglBase by lazy { EglBase.create() }

    private val disposer by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mobilerun-stream-dispose").apply { isDaemon = true }
        }
    }

    /** The shared factory; the first call initialises libwebrtc, which takes a moment. */
    fun factory(context: Context): PeerConnectionFactory =
        factoryInstance ?: synchronized(lock) {
            factoryInstance ?: create(context.applicationContext).also { factoryInstance = it }
        }

    /** Loads libwebrtc on a worker thread so that the first peer connection does not wait for it. */
    fun prewarm(context: Context) {
        if (factoryInstance != null) return
        Thread({ runCatching { factory(context) } }, "mobilerun-stream-init").start()
    }

    /** Runs [release] after a short delay on a background thread (native disposal of a peer connection). */
    fun disposeLater(release: () -> Unit) {
        disposer.schedule({ runCatching(release) }, DISPOSE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun captureFailed() { captureErrors.forEach { it() } }

    private fun create(appContext: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) = captureFailed()
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String,
                ) = captureFailed()
                override fun onWebRtcAudioRecordError(errorMessage: String) = captureFailed()
            }).createAudioDeviceModule()
        return try { PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        } finally { audioModule.release() }
    }
}

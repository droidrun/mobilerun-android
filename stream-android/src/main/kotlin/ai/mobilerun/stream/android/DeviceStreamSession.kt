// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentialsProvider
import ai.mobilerun.stream.core.StreamSessionEngine
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.core.VideoSize
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A live stream session to one Mobilerun cloud device: signalling WebSocket plus WebRTC, with the
 * same protocol and recovery behaviour as `@mobilerun/react`.
 *
 * The session connects on [start], reports its progress in [status], exposes the received video as
 * [videoTrack] for a renderer, and sends input as raw control messages ([sendControl],
 * [sendHover]). When the connection drops it rebuilds itself with exponential backoff and asks the
 * [credentials] provider for fresh credentials; after six failed attempts it reports
 * [StreamStatus.Unavailable] until [retry] is called. It has no Android lifecycle coupling: the
 * app decides when to [start] and [close].
 *
 * **Threading.** Pass a [scope] on a background dispatcher (for example `Dispatchers.Default`). The
 * session runs its work one step at a time on that scope's dispatcher (made serial with
 * `limitedParallelism(1)`); the connection setup is a few milliseconds of native calls that should
 * not run on the main thread. All members may be called from any thread. The session ends when
 * [close] is called or [scope] is cancelled.
 *
 * **Process-wide WebRTC state.** All sessions share one libwebrtc `PeerConnectionFactory` and one
 * `EglBase`, created on first use and kept for the life of the process. [close] releases a session's
 * peer connection, data channels and tracks, not those two.
 *
 * @param context any context; only the application context is kept.
 * @param credentials where the stream is and how to open it, see [StreamCredentialsProvider].
 * @param config options.
 * @param scope scope that bounds the session's lifetime and provides its dispatcher.
 */
public class DeviceStreamSession internal constructor(
    context: Context,
    credentials: StreamCredentialsProvider,
    private val config: StreamConfig,
    scope: CoroutineScope,
    signalling: SignallingTransportFactory,
) {
    /** Creates a session; nothing happens until [start]. */
    public constructor(
        context: Context,
        credentials: StreamCredentialsProvider,
        config: StreamConfig = StreamConfig(),
        scope: CoroutineScope,
    ) : this(
        context,
        credentials,
        config,
        scope,
        OkHttpSignallingTransportFactory(config.httpClient ?: defaultHttpClient),
    )

    private val appContext = context.applicationContext
    private val media = StreamMedia(config.muted)
    private val engine = StreamSessionEngine(
        scope = serial(scope),
        credentials = credentials,
        signalling = signalling,
        peers = WebRtcPeerTransportFactory({ WebRtcRuntime.factory(appContext) }, media, config.videoCodec),
        logger = config.logger,
    )

    /** The connection status. */
    public val status: StateFlow<StreamStatus> get() = engine.status

    /** Size and rotation of the received video; `null` until the first frame and while reconnecting. */
    public val videoSize: StateFlow<VideoSize?> get() = media.videoSize

    /**
     * The received video track for a renderer; `null` while there is none. It changes with every
     * reconnect and must not be used after it was replaced: collect this flow and attach or detach
     * your renderer on each change.
     */
    public val videoTrack: StateFlow<VideoTrack?> get() = media.videoTrack

    /** The EGL context that renderers must be initialised with to show [videoTrack] without copies. */
    public val eglBaseContext: EglBase.Context get() = WebRtcRuntime.eglBase.eglBaseContext

    /** Starts connecting. Does nothing if already started or closed. */
    public fun start() {
        WebRtcRuntime.prewarm(appContext)
        engine.start()
    }

    /** Starts a new reconnect cycle after the status became [StreamStatus.Unavailable]; otherwise does nothing. */
    public fun retry(): Unit = engine.retry()

    /** Stops the session and releases its connection, channels and video. A closed session cannot be restarted. */
    public fun close() {
        engine.close()
    }

    /** Silences or enables the device audio. */
    public fun setMuted(muted: Boolean): Unit = media.setMuted(muted)

    /**
     * Sends [bytes], a control message such as `ControlMessages.touch(...)`, on the control channel.
     * Returns `false`, dropping them, if the channel is not open.
     */
    public fun sendControl(bytes: ByteArray): Boolean = engine.sendControl(bytes)

    /**
     * Sends [bytes], a hover message, on the hover channel only, never on the control channel.
     * Returns `false`, dropping them, if the channel is not open.
     */
    public fun sendHover(bytes: ByteArray): Boolean = engine.sendHover(bytes)

    /** Asks the device to open [url]: sent now if the connection is up, otherwise when it is. */
    public fun openUrl(url: String): Unit = engine.openUrl(url)

    /**
     * Takes a screenshot of the device.
     *
     * @return the image bytes, normally PNG.
     * @throws ai.mobilerun.stream.core.StreamNotConnectedException if the signalling socket is not open.
     * @throws ai.mobilerun.stream.core.ScreenshotFailedException if the device could not take it.
     * @throws ai.mobilerun.stream.core.ScreenshotTimeoutException if no reply arrived within [timeout].
     */
    public suspend fun screenshot(timeout: Duration = 30.seconds): ByteArray = engine.screenshot(timeout)

    private companion object {
        val defaultHttpClient: OkHttpClient by lazy { OkHttpClient() }

        // The engine needs a dispatcher that runs one coroutine at a time.
        @OptIn(ExperimentalCoroutinesApi::class)
        fun serial(scope: CoroutineScope): CoroutineScope {
            val base = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
            return CoroutineScope(scope.coroutineContext + base.limitedParallelism(1))
        }
    }
}

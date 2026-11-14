// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.InternalStreamApi
import ai.mobilerun.stream.core.PeerTransportFactory
import ai.mobilerun.stream.core.ReconnectPolicy
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentialsProvider
import ai.mobilerun.stream.core.StreamSessionEngine
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.core.VideoSize
import ai.mobilerun.stream.protocol.SignallingTiming
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A live stream session to one Mobilerun cloud device: signalling WebSocket plus WebRTC, with the
 * same protocol and recovery behaviour as `@mobilerun/react`.
 *
 * The session connects on [start], reports its progress in [status], exposes the received video
 * through the Android view. Input mapping and channel sends are handled by the view. When the
 * connection drops it rebuilds itself with exponential backoff and asks the
 * [credentials] provider for fresh credentials. After [ReconnectPolicy.MAX_ATTEMPTS] failed attempts
 * it reports
 * [StreamStatus.Unavailable] until [retry] is called. It has no Android lifecycle coupling: the
 * app decides when to [start] and [close].
 *
 * **Threading.** Pass a [scope] on a background dispatcher (for example `Dispatchers.Default`): the
 * connection setup is a few milliseconds of native calls that should not run on the main thread.
 * All members may be called from any thread. The session ends, releasing everything on its own
 * serial context, when [close] is called or [scope] is cancelled.
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
    scope: CoroutineScope,
    config: StreamConfig,
    signalling: SignallingTransportFactory,
    private val peers: PeerTransportFactory? = null,
) {
    /** Creates a session; nothing happens until [start]. */
    public constructor(
        context: Context,
        credentials: StreamCredentialsProvider,
        scope: CoroutineScope,
        config: StreamConfig = StreamConfig(),
    ) : this(
        context,
        credentials,
        scope,
        config,
        OkHttpSignallingTransportFactory(config.httpClient ?: defaultHttpClient),
    )

    private val appContext = context.applicationContext
    private val media = StreamMedia(config.muted)
    private val engine = StreamSessionEngine(
        scope = scope,
        credentials = credentials,
        signalling = { target, listener ->
            media.beginAttempt()
            signalling.open(target, listener)
        },
        peers = peers ?: WebRtcPeerTransportFactory(
            { WebRtcRuntime.factory(appContext) }, media, config.videoCodec),
        logger = config.logger,
        onPeerConnected = { peer, attemptScope -> (peer as? WebRtcPeerTransport)?.startStats(attemptScope) },
        onPeerDisconnected = { peer -> (peer as? WebRtcPeerTransport)?.stopStats() },
    )

    /** The connection status. */
    public val status: StateFlow<StreamStatus> get() = engine.status

    /** Size and rotation of the received video; `null` until the first frame and while reconnecting. */
    public val videoSize: StateFlow<VideoSize?> get() = media.videoSize

    /** Inbound-video statistics sampled each second while connected; null otherwise. */
    public val stats: StateFlow<StreamStats?> = media.stats.asStateFlow()

    /**
     * The received video track for a renderer; `null` while there is none. It changes with every
     * reconnect and must not be used after it was replaced: collect this flow and attach or detach
     * your renderer on each change.
     */
    internal val videoTrack: StateFlow<VideoTrack?> get() = media.videoTrack

    /** The EGL context that renderers must be initialised with to show [videoTrack] without copies. */
    internal val eglBaseContext: EglBase.Context get() = WebRtcRuntime.eglBase.eglBaseContext

    /** Starts connecting. Does nothing if already started or closed. */
    public fun start() {
        if (peers == null) WebRtcRuntime.prewarm(appContext)
        engine.start()
    }

    /** Starts a new cycle after [StreamStatus.Unavailable]; otherwise does nothing. */
    public fun retry(): Unit = engine.retry()

    /** Pauses keep-alive messages while the host is hidden; does not end the session. */
    public fun setHostVisible(visible: Boolean): Unit = engine.setHostVisible(visible)

    /** Retries a pending recovery immediately, or revives an unavailable session. Never starts a closed session. */
    public fun handleWake(): Unit = engine.handleWake()

    /** Releases the connection, channels and video. A closed session cannot be restarted. */
    public fun close() {
        engine.close()
    }

    /** Silences or enables the device audio. */
    public fun setMuted(muted: Boolean): Unit = media.setMuted(muted)

    /**
     * Sends [bytes], a control message such as `ControlMessages.touch(...)`, on the control channel.
     * Returns `false`, dropping them, if the channel is not open.
     */
    @InternalStreamApi
    public fun sendControl(bytes: ByteArray): Boolean = engine.sendControl(bytes)

    /**
     * Sends [bytes], a hover message, on the hover channel only, never on the control channel.
     * Returns `false`, dropping them, if the channel is not open.
     */
    @InternalStreamApi
    public fun sendHover(bytes: ByteArray): Boolean = engine.sendHover(bytes)

    /** Asks the device to open [url]: sent now if the connection is up, otherwise when it is. */
    public fun openUrl(url: String): Unit = engine.openUrl(url)

    /**
     * Takes a screenshot of the device.
     *
     * @return the image bytes, normally PNG.
     * @throws ai.mobilerun.stream.core.StreamNotConnectedException if the signalling socket is not open.
     * @throws ai.mobilerun.stream.core.StreamDisconnectedException if the connection ended while waiting.
     * @throws ai.mobilerun.stream.core.ScreenshotFailedException if the device could not take it.
     * @throws ai.mobilerun.stream.core.ScreenshotTimeoutException if no reply arrived within [timeout].
     */
    public suspend fun screenshot(
        timeout: Duration = SignallingTiming.SCREENSHOT_TIMEOUT_MS.milliseconds,
    ): ByteArray = engine.screenshot(timeout)

    private companion object {
        val defaultHttpClient: OkHttpClient by lazy { OkHttpClient() }
    }
}

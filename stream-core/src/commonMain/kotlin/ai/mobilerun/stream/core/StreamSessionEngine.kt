// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.ClientMessage
import ai.mobilerun.stream.protocol.DataChannelSpec
import ai.mobilerun.stream.protocol.DataChannels
import ai.mobilerun.stream.protocol.IceServer
import ai.mobilerun.stream.protocol.ServerFrame
import ai.mobilerun.stream.protocol.ServerMessage
import ai.mobilerun.stream.protocol.SignallingPhase
import ai.mobilerun.stream.protocol.SignallingTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Tunables of [StreamSessionEngine] that are not part of the protocol. */
public class StreamEngineOptions(
    /** How often the credentials provider is asked again while it keeps returning `null`. */
    public val credentialsPollIntervalMs: Long = 2_000,
)

/**
 * The connection logic of one device stream, independent of any platform: it drives a
 * [SignallingTransport] and a [PeerTransport] exactly like `use-webrtc-connection.ts` of
 * `@mobilerun/react` does and recovers through the [StreamReconnectController] like
 * `use-stream-reconnect.ts` does.
 *
 * ## Threading
 * All state lives on the dispatcher of [scope], which **must run coroutines one at a time** (a
 * single thread, `limitedParallelism(1)`, or a test dispatcher). Transport callbacks may come from
 * any thread; they are posted to [scope] in order. [sendControl], [sendHover] and [close] may be
 * called from any thread, the other functions too (they hop onto [scope]).
 *
 * ## Deviation from the browser behaviour
 * The reference client lets the peer connection decide when the signalling socket dies while media
 * still flows. The bridge ends the session when the socket closes, so here the end of the socket,
 * before or after it opened, always counts as a connection failure and rebuilds the whole
 * connection.
 *
 * ## Credentials
 * The provider is called at [start], once per reconnect cycle (the reconnect controller's heal),
 * and when the server rejects the token (HTTP 401 or 403 on the upgrade). New credentials are applied
 * with a reconnect, except while a backoff wait or the unavailable state is in progress: then they
 * are held for the next attempt (same rule as the reference client).
 *
 * @param scope scope whose dispatcher runs the engine; the engine's work is a child of its job.
 * @param credentials where the stream is and how to open it.
 * @param signalling opens the signalling socket.
 * @param peers creates the peer connection.
 * @param options see [StreamEngineOptions].
 * @param random jitter source of the reconnect backoff, in `[0, 1)`.
 * @param nowMs a monotonic millisecond clock; tests pass the virtual clock.
 * @param newSessionId creates the session id of a connection attempt.
 * @param logger optional diagnostics.
 */
public class StreamSessionEngine(
    scope: CoroutineScope,
    private val credentials: StreamCredentialsProvider,
    private val signalling: SignallingTransportFactory,
    private val peers: PeerTransportFactory,
    private val options: StreamEngineOptions = StreamEngineOptions(),
    random: () -> Double = { Random.nextDouble() },
    private val nowMs: () -> Long = monotonicMillis(),
    private val newSessionId: () -> String = ::randomSessionId,
    private val logger: StreamLogger? = null,
) {
    private val callerContext = scope.coroutineContext
    private val job = SupervisorJob(callerContext[Job])
    private val engineScope = CoroutineScope(callerContext + job)

    private val mutableStatus = MutableStateFlow<StreamStatus>(StreamStatus.Connecting)

    /** The connection status. */
    public val status: StateFlow<StreamStatus> = mutableStatus.asStateFlow()

    private val controller = StreamReconnectController(
        onRemount = ::remount,
        onSnapshot = ::onSnapshot,
        onHeal = ::requestCredentialRefresh,
        scheduler = { delayMs, action ->
            val timer = launchGuarded { delay(delayMs); action() }
            ScheduledTask { timer.cancel() }
        },
        random = random,
    )

    @Volatile private var closed = false
    private var started = false

    // The newest credentials the provider returned, and the ones the live connection was opened with.
    private var latestTarget: StreamCredentials? = null
    private var activeTarget: StreamCredentials? = null
    private var credentialsJob: Job? = null

    @Volatile private var connection: Connection? = null
    private var pendingOpenUrl: String? = null

    /** Starts connecting. Does nothing if already started or closed. */
    public fun start() {
        launchGuarded {
            if (started || closed) return@launchGuarded
            started = true
            while (!closed) {
                val target = fetchCredentials()
                if (target != null) {
                    latestTarget = target
                    remount()
                    return@launchGuarded
                }
                log("credentials not ready")
                delay(options.credentialsPollIntervalMs)
            }
        }
    }

    /** Starts a fresh reconnect cycle after the stream became [StreamStatus.Unavailable]; otherwise does nothing. */
    public fun retry() {
        launchGuarded { controller.retry() }
    }

    /** Stops the session and releases the connection. Afterwards nothing happens anymore. */
    public fun close() {
        if (closed) return
        closed = true
        controller.dispose()
        job.cancel()
        val live = connection
        connection = null
        live?.release()
    }

    /**
     * Sends [bytes] on the control channel. Returns `false`, dropping them, if the channel is not
     * open.
     */
    public fun sendControl(bytes: ByteArray): Boolean = connection?.sendControl(bytes) ?: false

    /**
     * Sends [bytes] on the hover channel only, never on the control channel. Returns `false`,
     * dropping them, if the hover channel is not open.
     */
    public fun sendHover(bytes: ByteArray): Boolean = connection?.sendHover(bytes) ?: false

    /**
     * Asks the device to open [url]. Sent right away if the control channel is open; otherwise it is
     * sent once when the next connection's control channel opens.
     */
    public fun openUrl(url: String) {
        launchGuarded {
            val live = connection
            if (live != null && live.controlOpen) {
                live.sendOpenUrl(url)
            } else {
                pendingOpenUrl = url
            }
        }
    }

    /**
     * Takes a screenshot of the device through the signalling socket.
     *
     * @return the image bytes, normally PNG.
     * @throws StreamNotConnectedException if the socket is not open.
     * @throws StreamDisconnectedException if the connection ended while waiting.
     * @throws ScreenshotFailedException if the device could not take it.
     * @throws ScreenshotTimeoutException if no reply arrived within [timeout].
     */
    public suspend fun screenshot(timeout: Duration = SignallingTiming.SCREENSHOT_TIMEOUT_MS.milliseconds): ByteArray =
        withContext(callerContext.minusKey(Job)) {
            val live = connection?.takeIf { it.wsOpen } ?: throw StreamNotConnectedException()
            val id = "ui-ss-${nowMs()}-${randomSessionId().take(7)}"
            val reply = CompletableDeferred<ByteArray>()
            live.screenshots[id] = reply
            try {
                if (!live.send(ClientMessage.ScreenshotRequest(id))) throw StreamNotConnectedException()
                withTimeout(timeout) { reply.await() }
            } catch (_: TimeoutCancellationException) {
                throw ScreenshotTimeoutException()
            } finally {
                live.screenshots.remove(id)
            }
        }

    // ---- connection lifecycle -------------------------------------------------------------------

    /** The reconnect controller's mount: tear down the connection and start a new one on the latest target. */
    private fun remount() {
        if (closed) return
        val target = latestTarget ?: return
        releaseConnection()
        activeTarget = target
        val next = Connection(target)
        connection = next
        controller.attemptMounted()
        next.start()
    }

    private fun releaseConnection() {
        val old = connection
        connection = null
        old?.release()
    }

    private fun onSnapshot(snapshot: StreamReconnectSnapshot) {
        mutableStatus.value = snapshot.toStatus()
        log("status ${snapshot.status} attempt=${snapshot.attempt}")
        // A target change that was held back may be applicable now.
        launchGuarded { applyPendingTarget() }
    }

    private fun applyPendingTarget() {
        val latest = latestTarget ?: return
        val active = activeTarget ?: return
        if (latest == active || controller.shouldDeferTargetChange()) return
        log("applying new credentials")
        remount()
    }

    private suspend fun fetchCredentials(): StreamCredentials? =
        try {
            credentials.credentials()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("credentials failed: ${e::class.simpleName}")
            null
        }

    /** Asks the provider for fresh credentials unless a request is already running. */
    private fun requestCredentialRefresh() {
        if (closed || credentialsJob?.isActive == true) return
        credentialsJob = launchGuarded {
            val fresh = fetchCredentials() ?: return@launchGuarded
            latestTarget = fresh
            applyPendingTarget()
        }
    }

    private fun launchGuarded(block: suspend () -> Unit): Job = engineScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A bug or a transport error must never crash the host app.
            log("internal error: ${e::class.simpleName}")
        }
    }

    private fun log(message: String) {
        logger?.log(message)
    }

    // ---- one connection attempt -----------------------------------------------------------------

    /** One WebSocket plus one peer connection; everything about it is dropped when it is released. */
    private inner class Connection(private val target: StreamCredentials) : SignallingListener, PeerListener {
        private val sessionId = newSessionId()
        private var ws: SignallingTransport? = null
        private var peer: PeerTransport? = null
        private var phase = SignallingPhase.AwaitingRtcConfiguration
        private var released = false
        private var startupFailed = false
        private val timers = mutableListOf<Job>()
        private var handshakeTimer: Job? = null
        private var restartRecheck: Job? = null

        @Volatile var wsOpen = false
            private set
        @Volatile var controlOpen = false
            private set
        @Volatile private var hoverOpen = false

        // Remote candidates wait here until the remote description (or the restart answer) is set.
        private var remoteDescriptionSet = false
        private val pendingCandidates = mutableListOf<ServerMessage.Candidate>()

        // Until this time non-terminal connection-state dips are suppressed (ICE restart grace).
        private var iceRestartGraceUntil = 0L

        val screenshots = HashMap<String, CompletableDeferred<ByteArray>>()

        private val isCurrent: Boolean get() = !released && connection === this

        fun start() {
            try {
                ws = signalling.open(target, this)
            } catch (e: Exception) {
                log("signalling open failed: ${e::class.simpleName}")
                fail()
                return
            }
            handshakeTimer = after(SignallingTiming.WS_OPEN_TIMEOUT_MS) {
                log("websocket open timeout")
                failStartup()
            }
            // Armed at mount, not at open: ticks before the socket is open are skipped.
            timers += launchGuarded {
                while (true) {
                    delay(SignallingTiming.KEEP_ALIVE_INTERVAL_MS)
                    if (isCurrent && wsOpen) send(ClientMessage.KeepAlive(sessionId))
                }
            }
        }

        fun release() {
            if (released) return
            released = true
            wsOpen = false
            controlOpen = false
            hoverOpen = false
            timers.forEach { it.cancel() }
            timers.clear()
            restartRecheck?.cancel()
            val pending = screenshots.values.toList()
            screenshots.clear()
            pending.forEach { it.completeExceptionally(StreamDisconnectedException()) }
            runCatching { ws?.close() }
            runCatching { peer?.close() }
        }

        fun send(message: ClientMessage): Boolean = ws?.send(message.toJson()) ?: false

        fun sendOpenUrl(url: String) {
            send(ClientMessage.OpenUrl.create(url, sessionId))
        }

        fun sendControl(bytes: ByteArray): Boolean = controlOpen && peer?.sendControl(bytes) == true

        fun sendHover(bytes: ByteArray): Boolean = hoverOpen && peer?.sendHover(bytes) == true

        // A failure of this attempt: the reconnect controller decides what happens next.
        private fun fail() {
            if (!isCurrent) return
            controller.handleFailure()
        }

        private fun failStartup() {
            if (!isCurrent || startupFailed) return
            startupFailed = true
            handshakeTimer?.cancel()
            fail()
        }

        private fun after(delayMs: Long, action: () -> Unit): Job {
            val timer = launchGuarded { delay(delayMs); action() }
            timers += timer
            return timer
        }

        private fun post(block: suspend () -> Unit) {
            launchGuarded { if (isCurrent) block() }
        }

        // ---- SignallingListener (any thread) ----

        override fun onOpen() = post {
            if (startupFailed) return@post
            wsOpen = true
            handshakeTimer?.cancel()
            send(ClientMessage.RequestRtcConfiguration(sessionId))
            handshakeTimer = after(SignallingTiming.RTC_CONFIG_TIMEOUT_MS) {
                log("rtcConfiguration timeout")
                failStartup()
            }
        }

        override fun onClosed() = post {
            wsOpen = false
            if (startupFailed) return@post
            log("websocket closed")
            fail()
        }

        override fun onFailure(httpStatus: Int?) = post {
            wsOpen = false
            if (startupFailed) return@post
            log("websocket failed http=$httpStatus")
            fail()
            if (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN) requestCredentialRefresh()
        }

        override fun onMessage(text: String) = post {
            if (startupFailed) return@post
            when (val frame = ServerMessage.parse(text, phase)) {
                ServerFrame.Ignored -> Unit
                ServerFrame.StartupFailure -> {
                    log("invalid message while awaiting rtcConfiguration")
                    failStartup()
                }
                is ServerFrame.Message -> handle(frame.message)
            }
        }

        private suspend fun handle(message: ServerMessage) {
            when (message) {
                is ServerMessage.RtcConfiguration ->
                    if (phase == SignallingPhase.AwaitingRtcConfiguration) startPeer(message) else refresh(message)
                is ServerMessage.Answer -> applyAnswer(message)
                is ServerMessage.Candidate -> applyCandidate(message)
                is ServerMessage.Screenshot -> screenshots.remove(message.id)?.let { reply ->
                    try {
                        reply.complete(decodeDataUri(message.dataUri))
                    } catch (e: IllegalArgumentException) {
                        reply.completeExceptionally(ScreenshotFailedException("invalid screenshot data"))
                    }
                }
                is ServerMessage.ScreenshotError ->
                    screenshots.remove(message.id)?.completeExceptionally(ScreenshotFailedException(message.message))
            }
        }

        // ---- negotiation ----

        private suspend fun startPeer(message: ServerMessage.RtcConfiguration) {
            handshakeTimer?.cancel()
            phase = SignallingPhase.Established
            remoteDescriptionSet = false
            pendingCandidates.clear()
            iceRestartGraceUntil = 0
            try {
                val servers = message.iceServers
                if (servers == null && message.configuration?.containsKey("iceServers") == true) {
                    throw IllegalArgumentException("malformed ice servers")
                }
                val policy = (message.configuration?.get("iceTransportPolicy") as? JsonPrimitive)?.content
                val created = peers.create(
                    PeerConfiguration(servers.orEmpty(), policy),
                    listOf(DataChannels.control, DataChannels.hover),
                    this,
                )
                peer = created
                if (!isCurrent) return
                val sdp = created.createOffer(iceRestart = false)
                if (!isCurrent || peer !== created) return
                send(ClientMessage.Offer(sdp, sessionId))
                log("offer sent")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent) return
                log("negotiation failed: ${e::class.simpleName}")
                startupFailed = true
                fail()
            }
        }

        private suspend fun applyAnswer(message: ServerMessage.Answer) {
            val active = peer ?: return
            try {
                active.setRemoteAnswer(message.sdp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A stale answer (for a superseded restart offer) must not break the handler.
                log("setRemoteAnswer failed, ignoring answer")
                return
            }
            if (!isCurrent) return
            remoteDescriptionSet = true
            // Candidates that arrived before the answer, in arrival order.
            val buffered = pendingCandidates.toList()
            pendingCandidates.clear()
            for (candidate in buffered) {
                if (!isCurrent) return
                addCandidate(active, candidate)
            }
        }

        private suspend fun applyCandidate(message: ServerMessage.Candidate) {
            val active = peer ?: return
            if (!remoteDescriptionSet) {
                pendingCandidates += message
            } else {
                addCandidate(active, message)
            }
        }

        private suspend fun addCandidate(active: PeerTransport, message: ServerMessage.Candidate) {
            try {
                active.addRemoteCandidate(message.candidate, message.sdpMid, message.sdpMLineIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("addRemoteCandidate failed")
            }
        }

        // Mid-session credential refresh: new ICE servers, then an ICE-restart offer. Media keeps
        // flowing on the old candidate pair meanwhile.
        private suspend fun refresh(message: ServerMessage.RtcConfiguration) {
            val active = peer ?: return
            val servers = message.iceServers ?: return
            val applied = try {
                active.setIceServers(servers)
            } catch (e: Exception) {
                false
            }
            if (!applied) {
                log("refresh: setIceServers rejected, skipping ICE restart")
                return
            }
            iceRestartGraceUntil = nowMs() + SignallingTiming.ICE_RESTART_GRACE_MS
            try {
                val sdp = active.createOffer(iceRestart = true)
                if (!isCurrent || peer !== active) return
                // New ICE generation: buffer remote candidates until the restart answer arrives.
                remoteDescriptionSet = false
                pendingCandidates.clear()
                send(ClientMessage.Offer(sdp, sessionId))
                log("ICE-restart offer sent")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent) return
                iceRestartGraceUntil = 0
                log("refresh: ICE restart failed")
            }
        }

        // ---- PeerListener (any thread) ----

        override fun onLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) = post {
            send(ClientMessage.Candidate(candidate, sdpMid, sdpMLineIndex, sessionId))
        }

        override fun onConnectionState(state: PeerConnectionState) = post { handleConnectionState(state) }

        override fun onChannelState(channel: DataChannelSpec, open: Boolean) {
            when (channel.id) {
                DataChannels.control.id -> controlOpen = open
                DataChannels.hover.id -> hoverOpen = open
            }
            if (open && channel.id == DataChannels.control.id) post { onControlOpened() }
        }

        private fun onControlOpened() {
            log("control channel open")
            // Like the reference client: schedule the frame requests, then send the URL, so the
            // URL goes out first and the requests follow at +0, +125, ... ms.
            for (i in 0 until SignallingTiming.REQUEST_FRAME_COUNT) {
                timers += launchGuarded {
                    delay(i * SignallingTiming.REQUEST_FRAME_INTERVAL_MS)
                    if (isCurrent) send(ClientMessage.RequestFrame(sessionId))
                }
            }
            pendingOpenUrl?.let {
                pendingOpenUrl = null
                sendOpenUrl(it)
            }
        }

        private fun handleConnectionState(state: PeerConnectionState) {
            log("peer connection $state")
            val terminal = state == PeerConnectionState.Failed || state == PeerConnectionState.Closed
            if (terminal) controller.handleFailure()
            val connected = state == PeerConnectionState.Connected
            if (connected) {
                iceRestartGraceUntil = 0
            } else if (nowMs() < iceRestartGraceUntil && !terminal) {
                // Media still flows on the old pair during a restart; recheck when the window ends.
                scheduleRestartRecheck()
                return
            }
            restartRecheck?.cancel()
            restartRecheck = null
            if (connected) controller.handleConnected() else controller.handleDisconnected()
        }

        private fun scheduleRestartRecheck() {
            if (restartRecheck != null) return
            val watched = peer ?: return
            restartRecheck = launchGuarded {
                delay(maxOf(0L, iceRestartGraceUntil - nowMs()))
                if (!isCurrent || peer !== watched) return@launchGuarded
                restartRecheck = null
                if (nowMs() < iceRestartGraceUntil) {
                    scheduleRestartRecheck()
                    return@launchGuarded
                }
                if (watched.connectionState == PeerConnectionState.Connected) return@launchGuarded
                log("ICE-restart grace expired")
                controller.handleDisconnected()
            }.also { timers += it }
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/** `data:<mime>;base64,<payload>` to the payload bytes. */
internal fun decodeDataUri(dataUri: String): ByteArray {
    val marker = ";base64,"
    val at = dataUri.indexOf(marker)
    require(dataUri.startsWith("data:") && at >= 0) { "not a base64 data URI" }
    return Base64.decode(dataUri.substring(at + marker.length))
}

internal fun randomSessionId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(24) { append(alphabet[Random.nextInt(alphabet.length)]) } }
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

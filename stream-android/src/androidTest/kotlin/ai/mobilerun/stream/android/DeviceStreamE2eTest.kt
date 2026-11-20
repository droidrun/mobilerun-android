// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.SignallingTransport
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentials
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.protocol.AMotionEvent
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.SignallingTiming
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val TAG = "StreamE2E"

/**
 * Runs against a real cloud device. The credentials are not part of the repository: the runner copies
 * `stream-e2e.json` (`streamUrl`, `streamToken`) into the test app's private files directory
 * before the run, and the test skips itself if the file is missing. Optional instrumentation arguments:
 * `tapX`, `tapY` (video pixels). Evidence (screenshots) is written to `files/e2e-out/`.
 */
class DeviceStreamE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val out = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private lateinit var device: E2eDevice
    private lateinit var scope: CoroutineScope
    private lateinit var tap: TappingSignalling
    private lateinit var peer: WebRtcPeerTransport
    private lateinit var session: DeviceStreamSession

    /** Wraps the real signalling: records the offers and answers and lets the test inject server frames. */
    private class TappingSignalling(
        private val inner: SignallingTransportFactory,
    ) : SignallingTransportFactory {
        val transports = CopyOnWriteArrayList<OkHttpSignallingTransport>()
        val configurations = CopyOnWriteArrayList<String>()
        val sentOffers = CopyOnWriteArrayList<String>()
        val receivedAnswers = CopyOnWriteArrayList<String>()
        @Volatile var listener: SignallingListener? = null

        override fun open(credentials: StreamCredentials, listener: SignallingListener): SignallingTransport {
            val spy = object : SignallingListener by listener {
                override fun onMessage(text: String) {
                    val type = runCatching { JSONObject(text).optString("type") }.getOrDefault("")
                    if (type == "rtcConfiguration") configurations += text
                    if (type == "answer") receivedAnswers += JSONObject(text).getString("sdp")
                    listener.onMessage(text)
                }
            }
            this.listener = spy
            val real = inner.open(credentials, spy) as OkHttpSignallingTransport
            transports += real
            return object : SignallingTransport by real {
                override fun send(text: String): Boolean {
                    if (runCatching { JSONObject(text).optString("type") }.getOrDefault("") == "offer") {
                        sentOffers += JSONObject(text).getString("sdp")
                    }
                    return real.send(text)
                }
            }
        }
    }

    private fun iceUfrag(sdp: String): String =
        Regex("a=ice-ufrag:(\\S+)").find(sdp)?.groupValues?.get(1) ?: error("no ice-ufrag in SDP")

    @Before
    fun setUp() {
        device = E2eDevice.load(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tap = TappingSignalling(device.signalling)
        val codec = VideoCodecPreference.valueOf(
            InstrumentationRegistry.getArguments().getString("videoCodec") ?: "Auto")
        session = DeviceStreamSession(
            context = context,
            credentials = { device.credentials },
            config = StreamConfig(videoCodec = codec),
            logger = { Log.i(TAG, it) },
            scope = scope,
            signalling = tap,
            peers = ai.mobilerun.stream.core.PeerTransportFactory { configuration, channels, listener ->
                WebRtcPeerTransportFactory({ WebRtcRuntime.factory(context) }, session.media,
                    codec).create(configuration, channels, listener).also { peer = it }
            },
        )
    }

    @After
    fun tearDown() {
        if (::session.isInitialized) session.close()
        if (::scope.isInitialized) scope.cancel()
    }

    private fun shot(name: String, bytes: ByteArray) {
        File(out, "$name.png").writeBytes(bytes)
        Log.i(TAG, "shot $name bytes=${bytes.size}")
    }

    /** Counts frames of whatever track is current until [stop]. */
    private class FrameCounter(session: DeviceStreamSession, scope: CoroutineScope) {
        val frames = AtomicInteger()
        private var attached: VideoTrack? = null
        private val sink = VideoSink { _: VideoFrame -> frames.incrementAndGet() }
        private val job = scope.launch {
            session.videoTrack.collect { track ->
                runCatching { attached?.removeSink(sink) }
                attached = track
                track?.addSink(sink)
            }
        }

        fun stop() = job.cancel()
    }

    private suspend fun framesGrow(counter: FrameCounter, within: Long = 15_000): Boolean {
        val start = counter.frames.get()
        return withTimeoutOrNull(within) {
            while (counter.frames.get() < start + 30) delay(100)
            true
        } ?: false
    }

    @Test
    fun goingAwayReleasesNativeMediaBeforeReconnect() = runBlocking {
        session.start()
        device.awaitConnectedWithVideo(session)
        val sockets = tap.transports.size
        val closingPeer = peer
        tap.listener!!.onClosed(1001)
        // Below the minimum first backoff (750 ms): release must precede a new mount.
        withTimeout(500) { session.videoTrack.first { it == null } }
        assertEquals(null, session.videoSize.value)
        assertFalse(closingPeer.sendControl(byteArrayOf(1)))
        assertFalse(closingPeer.sendHover(byteArrayOf(1)))
        assertEquals(sockets, tap.transports.size)
        assertTrue(session.status.value is StreamStatus.Reconnecting)
    }

    @Test
    fun firstFrameTapAndReconnect() = runBlocking {
        val counter = FrameCounter(session, scope)
        val t0 = SystemClock.elapsedRealtime()
        session.start()

        // 1. First frame.
        val size = device.awaitConnectedWithVideo(session)
        Log.i(TAG, "first frame after ${SystemClock.elapsedRealtime() - t0} ms size=$size")
        assertTrue(framesGrow(counter), "video frames keep arriving")
        delay(2_000)
        val before = session.screenshot()
        shot("1-before-tap", before)

        // 2. Tap through the control channel, encoded by the protocol module.
        val args = InstrumentationRegistry.getArguments()
        val x = args.getString("tapX")?.toDouble() ?: (size.width / 2.0)
        val y = args.getString("tapY")?.toDouble() ?: (size.height / 2.0)
        for (action in listOf(AMotionEvent.ACTION_DOWN, AMotionEvent.ACTION_UP)) {
            val bytes = ControlMessages.touch(
                action = action, pointerId = 1, videoWidth = size.width, videoHeight = size.height,
                x = x, y = y, pressure = if (action == AMotionEvent.ACTION_DOWN) 1.0 else 0.0,
                actionButton = AMotionEvent.BUTTON_PRIMARY,
                buttons = if (action == AMotionEvent.ACTION_DOWN) AMotionEvent.BUTTON_PRIMARY else 0,
            )
            assertTrue(session.sendControl(bytes), "control channel open")
        }
        delay(3_000)
        val after = session.screenshot()
        shot("2-after-tap", after)
        assertFalse(screenshotCrop(before).contentEquals(screenshotCrop(after)),
            "the tap changed the device screen")

        // 3. Forced WebSocket close: full rebuild, video again.
        val socketsBefore = tap.transports.size
        val statuses = CopyOnWriteArrayList<StreamStatus>()
        val watcher = scope.launchStatuses(statuses)
        tap.transports.last().socket.cancel()
        withTimeout(30.seconds) { session.status.first { it is StreamStatus.Reconnecting } }
        device.awaitConnectedWithVideo(session)
        assertTrue(tap.transports.size > socketsBefore, "a new socket was opened")
        assertTrue(framesGrow(counter), "video resumed after the rebuild")
        watcher.cancel()
        Log.i(TAG, "status sequence after ws close: $statuses")
        assertTrue(statuses.any { it is StreamStatus.Reconnecting })
        assertEquals(StreamStatus.Connected, session.status.value)
        delay(2_000)
        shot("3-after-reconnect", session.screenshot())

        counter.stop()
        assertNotEquals(0, counter.frames.get())
    }

    @Test
    fun liveConfigurationRefreshKeepsVideo() = runBlocking {
        val counter = FrameCounter(session, scope)
        session.start()
        device.awaitConnectedWithVideo(session)
        assertTrue(framesGrow(counter), "video frames arrive before refresh")
        // 4. Mid-session credential refresh. The server pushed none, so the configuration it sent
        // at the start is replayed as if it were a refresh. Proof: an ICE-restart offer goes out, the
        // server answers it, and both carry a new ICE ufrag; the stream is not rebuilt.
        val initialOffer = tap.sentOffers.last()
        val initialAnswer = tap.receivedAnswers.last()
        val offersBefore = tap.sentOffers.size
        val answersBefore = tap.receivedAnswers.size
        val connectedSockets = tap.transports.size
        tap.listener!!.onMessage(tap.configurations.first())
        withTimeout(30.seconds) {
            while (tap.sentOffers.size <= offersBefore || tap.receivedAnswers.size <= answersBefore) {
                delay(100)
            }
        }
        val restartOffer = tap.sentOffers.last()
        val restartAnswer = tap.receivedAnswers.last()
        Log.i(TAG, "ice ufrag offer ${iceUfrag(initialOffer)} -> ${iceUfrag(restartOffer)}, " +
            "answer ${iceUfrag(initialAnswer)} -> ${iceUfrag(restartAnswer)}")
        assertNotEquals(iceUfrag(initialOffer), iceUfrag(restartOffer),
            "the restart offer has a new ICE generation")
        assertNotEquals(iceUfrag(initialAnswer), iceUfrag(restartAnswer),
            "the answer belongs to the new ICE generation")
        // Wait out the 15 s grace window: the stream must still be up afterwards.
        delay(SignallingTiming.ICE_RESTART_GRACE_MS + 5_000)
        assertEquals(StreamStatus.Connected, session.status.value, "stream stays up through the refresh")
        assertEquals(connectedSockets, tap.transports.size, "no rebuild happened")
        assertTrue(framesGrow(counter), "video continues after the ICE restart")
        shot("4-after-refresh", session.screenshot())
        counter.stop()
        assertNotEquals(0, counter.frames.get())
    }

    private fun CoroutineScope.launchStatuses(into: MutableList<StreamStatus>) =
        launch { session.status.collect { into += it } }

    @Test
    fun candidateWithNullMidUsesMediaLineIndex() = runBlocking {
        session.start()
        device.awaitConnectedWithVideo(session)
        peer.addRemoteCandidate("candidate:1 1 UDP 2122260223 192.0.2.1 5000 typ host", null, 1)
        // The oracle is no native abort at the JNI boundary; candidate acceptance is asynchronous.
    }

}

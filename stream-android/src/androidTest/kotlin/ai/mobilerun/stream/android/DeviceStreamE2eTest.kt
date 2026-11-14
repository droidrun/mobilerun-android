// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.SignallingTransport
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentials
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.core.VideoSize
import ai.mobilerun.stream.protocol.AMotionEvent
import ai.mobilerun.stream.protocol.ControlMessages
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceStreamE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val out = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private lateinit var scope: CoroutineScope
    private lateinit var tap: TappingSignalling
    private lateinit var session: DeviceStreamSession

    private class TappingSignalling(private val inner: SignallingTransportFactory) : SignallingTransportFactory {
        val transports = CopyOnWriteArrayList<SignallingTransport>()
        val configurations = CopyOnWriteArrayList<String>()
        @Volatile var listener: SignallingListener? = null

        override fun open(credentials: StreamCredentials, listener: SignallingListener): SignallingTransport {
            val spy = object : SignallingListener by listener {
                override fun onMessage(text: String) {
                    if (text.contains("\"rtcConfiguration\"")) configurations += text
                    listener.onMessage(text)
                }
            }
            this.listener = spy
            return inner.open(credentials, spy).also { transports += it }
        }
    }

    @Before
    fun setUp() {
        val config = File(context.filesDir, "stream-e2e.json")
        assumeTrue("no stream-e2e.json in the app files dir", config.exists())
        val json = JSONObject(config.readText())
        val creds = StreamCredentials(json.getString("streamUrl"), json.getString("streamToken"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tap = TappingSignalling(OkHttpSignallingTransportFactory(okhttp3.OkHttpClient()))
        session = DeviceStreamSession(
            context = context,
            credentials = { creds },
            config = StreamConfig(logger = { Log.i(TAG, it) }),
            scope = scope,
            signalling = tap,
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

    private suspend fun awaitConnectedWithVideo(): VideoSize = withTimeout(60.seconds) {
        session.status.first { it == StreamStatus.Connected }
        session.videoSize.first { it != null }!!
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
    fun e2eFullScenario() = runBlocking {
        val counter = FrameCounter(session, scope)
        val t0 = SystemClock.elapsedRealtime()
        session.start()

        // 1. First frame.
        val size = awaitConnectedWithVideo()
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
        assertTrue(!before.contentEquals(after), "the tap changed the device screen")

        // 3. Forced WebSocket close: full rebuild, video again.
        val socketsBefore = tap.transports.size
        val statuses = CopyOnWriteArrayList<StreamStatus>()
        val watcher = scope.launchStatuses(statuses)
        (tap.transports.last() as OkHttpSignallingTransport).cancelAbruptly()
        withTimeout(30.seconds) { session.status.first { it is StreamStatus.Reconnecting } }
        awaitConnectedWithVideo()
        assertTrue(tap.transports.size > socketsBefore, "a new socket was opened")
        assertTrue(framesGrow(counter), "video resumed after the rebuild")
        watcher.cancel()
        Log.i(TAG, "status sequence after ws close: $statuses")
        assertTrue(statuses.any { it is StreamStatus.Reconnecting })
        assertEquals(StreamStatus.Connected, session.status.value)
        delay(2_000)
        shot("3-after-reconnect", session.screenshot())

        // 4. Mid-session credential refresh: replay the configuration the server sent as a refresh.
        val configuration = tap.configurations.first()
        val connectedSockets = tap.transports.size
        tap.listener!!.onMessage(configuration)
        delay(10_000)
        assertEquals(StreamStatus.Connected, session.status.value, "stream stays up through the refresh")
        assertEquals(connectedSockets, tap.transports.size, "no rebuild happened")
        assertTrue(framesGrow(counter), "video continues after the ICE restart")
        shot("4-after-refresh", session.screenshot())
        counter.stop()
        assertNotEquals(0, counter.frames.get())
    }

    private fun CoroutineScope.launchStatuses(into: MutableList<StreamStatus>) =
        launch { session.status.collect { into += it } }
}

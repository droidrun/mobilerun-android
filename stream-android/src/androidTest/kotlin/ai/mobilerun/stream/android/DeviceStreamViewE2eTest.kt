// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentials
import ai.mobilerun.stream.core.StreamStatus
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Test-only host; no credentials or connection configuration is bundled. */
class InputTestActivity : Activity() {
    lateinit var stream: DeviceStreamView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stream = DeviceStreamView(this)
        setContentView(stream)
    }
}

/** Real view-event paths against a live device. Targets are upright video pixels, supplied by the caller. */
class DeviceStreamViewE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val out = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: DeviceStreamSession
    private lateinit var activity: InputTestActivity
    private val view get() = activity.stream

    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @After
    fun cleanup() {
        if (::activity.isInitialized) ui { view.detach(); activity.finish() }
        if (::session.isInitialized) session.close()
        scope.cancel()
    }

    private suspend fun shot(name: String): ByteArray {
        delay(1200)
        val bytes = session.screenshot()
        File(out, "$name.png").writeBytes(bytes)
        // Also records the actual renderer and letterbox, not just the remote screenshot.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) {
            File(out, "$name-local.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        Log.i("StreamInputE2E", "shot $name")
        return bytes
    }

    private fun point(videoX: Double, videoY: Double): Pair<Float, Float> {
        val size = assertNotNull(session.videoSize.value)
        val rect = assertNotNull(view.renderedVideoRect)
        return (rect.left + rect.width() * videoX.toFloat() / size.displayWidth) to
            (rect.top + rect.height() * videoY.toFloat() / size.displayHeight)
    }

    private fun touch(action: Int, points: List<Pair<Float, Float>>, time: Long) {
        val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
            id = 7 + i
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
            x = px; y = py; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private suspend fun tap(x: Double, y: Double) {
        val time = SystemClock.uptimeMillis()
        ui {
            val p = point(x, y)
            Log.i("StreamInputE2E", "tap video=$x,$y local=$p")
            touch(MotionEvent.ACTION_DOWN, listOf(p), time)
        }
        delay(80)
        ui { touch(MotionEvent.ACTION_UP, listOf(point(x, y)), time) }
        delay(1000)
    }

    private suspend fun swipe(x: Double, from: Double, to: Double) {
        val time = SystemClock.uptimeMillis()
        ui { touch(MotionEvent.ACTION_DOWN, listOf(point(x, from)), time) }
        repeat(20) { index ->
            ui { touch(MotionEvent.ACTION_MOVE, listOf(point(x, from + (to - from) * (index + 1) / 20)), time) }
            delay(20)
        }
        ui { touch(MotionEvent.ACTION_UP, listOf(point(x, to)), time) }
        delay(1200)
    }

    @Test
    fun viewInputScenario() = runBlocking {
        val file = File(context.filesDir, "stream-e2e.json")
        assumeTrue("no runtime credentials", file.exists())
        val json = JSONObject(file.readText())
        val credentials = StreamCredentials(json.getString("streamUrl"), json.getString("streamToken"))
        val rejected = AtomicInteger()
        val real = OkHttpSignallingTransportFactory(okhttp3.OkHttpClient())
        val signalling = SignallingTransportFactory { creds, listener ->
            real.open(creds, object : SignallingListener by listener {
                override fun onFailure(httpStatus: Int?) {
                    if (httpStatus == 401 || httpStatus == 404) rejected.set(httpStatus)
                    Log.i("StreamInputE2E", "signalling failure HTTP ${httpStatus ?: 0}")
                    listener.onFailure(httpStatus)
                }
            })
        }
        session = DeviceStreamSession(context, { credentials }, scope, StreamConfig(), signalling)
        session.start()
        withTimeout(60.seconds) {
            while (session.status.value != StreamStatus.Connected && rejected.get() == 0) delay(100)
        }
        check(rejected.get() == 0) { "PENDING: device HTTP ${rejected.get()}" }
        withTimeout(30.seconds) { session.videoSize.first { it != null } }
        activity = instrumentation.startActivitySync(Intent(context, InputTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputTestActivity
        ui { view.attach(session) }
        withTimeout(10.seconds) {
            var ready = false
            while (!ready) { ui { ready = view.renderedVideoRect != null }; delay(50) }
        }
        delay(2000)
        ui { Log.i("StreamInputE2E", "video=${session.videoSize.value} rect=${view.renderedVideoRect}") }
        shot("0-connected")
        val size = assertNotNull(session.videoSize.value)
        // Optional screenshot dimensions let the caller choose targets from full-resolution shots
        // even when the decoded stream uses a smaller resolution.
        val scaleX = size.displayWidth / (args.getString("coordinateWidth")?.toDouble() ?: size.displayWidth.toDouble())
        val scaleY = size.displayHeight / (args.getString("coordinateHeight")?.toDouble() ?: size.displayHeight.toDouble())
        // Capture-only mode permits choosing known targets without guessing at the cloud screen.
        if (args.getString("captureOnly") == "true") {
            ui { view.sendSystemKey(SystemKey.HOME) }
            delay(1000)
            args.getString("tapX")?.let {
                tap(it.toDouble() * scaleX, requireNotNull(args.getString("tapY")).toDouble() * scaleY)
            }
            args.getString("prepareTapX")?.let {
                tap(it.toDouble() * scaleX, requireNotNull(args.getString("prepareTapY")).toDouble() * scaleY)
            }
            args.getString("openUrl")?.let { session.openUrl(it) }
            delay(2000)
            shot("0-capture")
            return@runBlocking
        }

        ui { view.sendSystemKey(SystemKey.HOME) }
        val home = shot("1-home")
        val tapX = requireNotNull(args.getString("tapX")) { "tapX is required" }.toDouble() * scaleX
        val tapY = requireNotNull(args.getString("tapY")) { "tapY is required" }.toDouble() * scaleY
        tap(tapX, tapY)
        val opened = shot("2-app-opened")
        assertFalse(home.contentEquals(opened), "tap opens the selected app")
        args.getString("openUrl")?.let { session.openUrl(it); delay(3000) }
        val beforeScroll = shot("2-before-scroll")
        val centerX = size.displayWidth / 2.0
        swipe(centerX, size.displayHeight * .8, size.displayHeight * .3)
        val scrolled = shot("3-scrolled")
        assertFalse(beforeScroll.contentEquals(scrolled), "swipe scrolls the list")

        ui {
            val (px, py) = point(centerX, size.displayHeight * .5)
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = px; y = py
                setAxisValue(MotionEvent.AXIS_VSCROLL, -3f)
            })
            for (action in listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL)) {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, 1, properties, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                try { assertTrue(view.dispatchGenericMotionEvent(event)) } finally { event.recycle() }
            }
        }
        shot("3-wheel")

        // Two stable platform ids become two wire slots. Cloud handling may support one pointer.
        val time = SystemClock.uptimeMillis()
        ui {
            touch(MotionEvent.ACTION_DOWN, listOf(point(centerX - 30, size.displayHeight * .5)), time)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(point(centerX - 30, size.displayHeight * .5), point(centerX + 30, size.displayHeight * .5)), time)
        }
        repeat(10) { index ->
            ui { touch(MotionEvent.ACTION_MOVE, listOf(point(centerX - 30 - index * 8, size.displayHeight * .5),
                point(centerX + 30 + index * 8, size.displayHeight * .5)), time) }
            delay(20)
        }
        ui {
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(point(centerX - 102, size.displayHeight * .5), point(centerX + 102, size.displayHeight * .5)), time)
            touch(MotionEvent.ACTION_UP, listOf(point(centerX - 102, size.displayHeight * .5)), time)
        }
        shot("4-pinch")
        Log.i("StreamInputE2E", "two-finger gesture dispatched with platform ids 7/8")

        // HOME resets to a known screen; rotate only the local viewport, then tap the same target.
        ui { view.sendSystemKey(SystemKey.HOME); activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        delay(2000)
        tap(tapX, tapY)
        shot("5-local-landscape-tap")
        ui { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        delay(1500)
        args.getString("textX")?.let { textX ->
            tap(textX.toDouble() * scaleX, requireNotNull(args.getString("textY")).toDouble() * scaleY)
        }
        ui {
            view.showKeyboard()
            val input = assertNotNull(view.onCreateInputConnection(EditorInfo()))
            input.setComposingText("mar", 1)
            input.commitText("mars", 1)
        }
        shot("6-ime-text")
        ui {
            view.hideKeyboard()
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B))
        }
        shot("7-hardware-text")
        ui {
            val input = assertNotNull(view.onCreateInputConnection(EditorInfo()))
            input.deleteSurroundingText(1, 0)
        }
        shot("8-backspace")
        ui {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", " paste"))
            val timeNow = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(KeyEvent(timeNow, timeNow, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0,
                KeyEvent.META_CTRL_ON))
            view.dispatchKeyEvent(KeyEvent(timeNow, timeNow, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0,
                KeyEvent.META_CTRL_ON))
        }
        shot("9-clipboard")
        ui { assertNotNull(view.onCreateInputConnection(EditorInfo())).performEditorAction(EditorInfo.IME_ACTION_GO) }
        shot("10-enter")
        ui { view.sendSystemKey(SystemKey.BACK) }
        shot("11-back")
        ui { view.sendSystemKey(SystemKey.RECENT) }
        shot("12-recents")
        ui { view.sendSystemKey(SystemKey.HOME) }
        shot("13-home")
        ui { view.hasControl = false; view.sendSystemKey(SystemKey.RECENT) }
        shot("14-display-only")
        Log.i("StreamInputE2E", "view input scenario complete")
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
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

/** Assertions compare decoded image crops, excluding the changing device status bar. */
class DeviceStreamViewE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val out = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: DeviceStreamSession
    private lateinit var activity: InputTestActivity
    private val view get() = activity.stream
    private val size get() = requireNotNull(session.videoSize.value)
    private val tapX get() = requireNotNull(args.getString("tapX")) { "tapX required" }.toDouble()
    private val tapY get() = requireNotNull(args.getString("tapY")) { "tapY required" }.toDouble()

    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Before
    fun connect() = runBlocking {
        val device = E2eDevice.load(context)
        session = DeviceStreamSession(context, { device.credentials }, scope,
            StreamConfig(), device.signalling)
        session.start()
        device.awaitConnectedWithVideo(session)
        activity = instrumentation.startActivitySync(Intent(context, InputTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputTestActivity
        ui { view.attach(session) }
        withTimeout(10.seconds) {
            var ready = false
            while (!ready) { ui { ready = view.renderedVideoRect != null }; delay(50) }
        }
        delay(2000)
    }

    @After
    fun cleanup() {
        if (::activity.isInitialized) ui { view.detach(); activity.finish() }
        if (::session.isInitialized) session.close()
        scope.cancel()
    }

    private suspend fun shot(name: String): ByteArray {
        delay(1500)
        val bytes = session.screenshot()
        File(out, "$name.png").writeBytes(bytes)
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

    private fun assertChanged(before: ByteArray, after: ByteArray, message: String, field: Boolean = false) {
        assertFalse(screenshotCrop(before, field).contentEquals(screenshotCrop(after, field)), message)
    }

    private fun point(x: Double, y: Double): Pair<Float, Float> {
        val rect = assertNotNull(view.renderedVideoRect)
        return (rect.left + rect.width() * x.toFloat() / size.displayWidth) to
            (rect.top + rect.height() * y.toFloat() / size.displayHeight)
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
        ui { touch(MotionEvent.ACTION_DOWN, listOf(point(x, y)), time) }
        delay(80)
        ui { touch(MotionEvent.ACTION_UP, listOf(point(x, y)), time) }
        delay(1000)
    }

    @Test
    fun touchScrollPinchAndLandscapeMapping() = runBlocking {
        ui { view.sendSystemKey(SystemKey.HOME) }
        val home = shot("touch-1-home")
        tap(tapX, tapY)
        assertChanged(home, shot("touch-2-app-opened"), "tap opens the selected app")
        session.openUrl(requireNotNull(args.getString("openUrl")) { "openUrl required for scroll target" })
        delay(3000)
        val before = shot("touch-3-before-scroll")
        val x = size.displayWidth / 2.0
        val time = SystemClock.uptimeMillis()
        ui { touch(MotionEvent.ACTION_DOWN, listOf(point(x, size.displayHeight * .8)), time) }
        repeat(20) { index ->
            ui { touch(MotionEvent.ACTION_MOVE,
                listOf(point(x, size.displayHeight * (.8 - .5 * (index + 1) / 20))), time) }
            delay(20)
        }
        ui { touch(MotionEvent.ACTION_UP, listOf(point(x, size.displayHeight * .3)), time) }
        val scrolled = shot("touch-4-scrolled")
        assertChanged(before, scrolled, "swipe scrolls content below the status bar")
        ui {
            val (px, py) = point(x, size.displayHeight * .5)
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = px; y = py; setAxisValue(MotionEvent.AXIS_VSCROLL, -3f)
            })
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try { assertTrue(view.dispatchGenericMotionEvent(event)) } finally { event.recycle() }
        }
        val wheeled = shot("touch-5-wheel")
        assertChanged(scrolled, wheeled, "wheel scrolls content")
        val pinchTime = SystemClock.uptimeMillis()
        ui {
            touch(MotionEvent.ACTION_DOWN, listOf(point(x - 30, size.displayHeight * .5)), pinchTime)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(point(x - 30, size.displayHeight * .5),
                    point(x + 30, size.displayHeight * .5)), pinchTime)
        }
        repeat(10) { index ->
            ui { touch(MotionEvent.ACTION_MOVE,
                listOf(point(x - 30 - index * 8, size.displayHeight * .5),
                    point(x + 30 + index * 8, size.displayHeight * .5)), pinchTime) }
            delay(20)
        }
        ui {
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(point(x - 102, size.displayHeight * .5),
                    point(x + 102, size.displayHeight * .5)), pinchTime)
            touch(MotionEvent.ACTION_UP, listOf(point(x - 102, size.displayHeight * .5)), pinchTime)
        }
        assertChanged(wheeled, shot("touch-6-pinch"), "two-finger gesture changes the page zoom")
        ui {
            view.sendSystemKey(SystemKey.HOME)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val landscapeHome = shot("touch-7-landscape-home")
        ui { assertTrue(view.width > view.height, "local viewport rotated") }
        tap(tapX, tapY)
        assertChanged(landscapeHome, shot("touch-8-landscape-tap"), "known target opens in landscape")
        ui { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    @Test
    fun typingClipboardNavigationAndDisplayOnly() = runBlocking {
        ui { view.sendSystemKey(SystemKey.HOME) }
        val home = shot("keys-1-home")
        tap(tapX, tapY)
        val opened = shot("keys-2-app-opened")
        assertChanged(home, opened, "tap opens app for typing")
        tap(requireNotNull(args.getString("textX")).toDouble(),
            requireNotNull(args.getString("textY")).toDouble())
        val field = shot("keys-3-field")
        ui {
            view.showKeyboard()
            val input = assertNotNull(view.onCreateInputConnection(EditorInfo()))
            input.setComposingText("mars", 1)
            input.finishComposingText()
        }
        val ime = shot("keys-4-ime")
        assertChanged(field, ime, "IME composition is committed into the visible field", field = true)
        ui {
            view.hideKeyboard()
            for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B)) {
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        val hardware = shot("keys-5-hardware")
        assertChanged(ime, hardware, "hardware characters appear in the field", field = true)
        ui { assertNotNull(view.onCreateInputConnection(EditorInfo())).deleteSurroundingText(1, 0) }
        val deleted = shot("keys-6-backspace")
        assertChanged(hardware, deleted, "Backspace changes the field", field = true)
        ui {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "paste"))
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                view.dispatchKeyEvent(KeyEvent(
                    now, now, action, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON))
            }
        }
        val pasted = shot("keys-7-clipboard")
        assertChanged(deleted, pasted, "clipboard paste changes the field", field = true)
        ui {
            assertNotNull(view.onCreateInputConnection(EditorInfo()))
                .performEditorAction(EditorInfo.IME_ACTION_GO)
        }
        val entered = shot("keys-8-enter")
        assertChanged(pasted, entered, "Enter submits the field")
        ui { view.sendSystemKey(SystemKey.BACK) }
        val back = shot("keys-9-back")
        assertChanged(entered, back, "BACK navigates to the prior page")
        ui { view.sendSystemKey(SystemKey.HOME) }
        val returnedHome = shot("keys-10-home")
        assertChanged(back, returnedHome, "HOME returns to launcher")
        ui { view.hasControl = false; view.sendSystemKey(SystemKey.RECENT) }
        assertContentEquals(screenshotCrop(returnedHome), screenshotCrop(shot("keys-11-display-only")),
            "display-only suppresses input; the content crop stays identical")
    }
}

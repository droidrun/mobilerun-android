// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.PeerConnectionState
import ai.mobilerun.stream.core.PeerTransport
import ai.mobilerun.stream.core.PeerTransportFactory
import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.SignallingTransport
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentials
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.core.VideoSize
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.IceServer
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowInputMethodManager
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DeviceStreamViewTest.OwningInputMethodManager::class])
class DeviceStreamViewTest {
    // Robolectric's stock isActive(view) always returns false; provide the platform owner signal.
    @Implements(InputMethodManager::class)
    class OwningInputMethodManager : ShadowInputMethodManager() {
        var owner: View? = null
        @Implementation
        public override fun isActive(view: View): Boolean = view === owner
    }

    private class RecordingPeer : PeerTransport {
        val control = mutableListOf<ByteArray>()
        val hover = mutableListOf<ByteArray>()
        override val connectionState = PeerConnectionState.Connected
        override suspend fun createOffer(iceRestart: Boolean) = "offer"
        override suspend fun setRemoteAnswer(sdp: String) = Unit
        override suspend fun addRemoteCandidate(
            candidate: String, sdpMid: String?, sdpMLineIndex: Int?,
        ) = Unit
        override fun setIceServers(iceServers: List<IceServer>) = true
        override fun sendControl(bytes: ByteArray): Boolean { control += bytes; return true }
        override fun sendHover(bytes: ByteArray): Boolean { hover += bytes; return true }
        override fun close() = Unit
    }

    private fun withView(block: (DeviceStreamView, RecordingPeer, Activity) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val peer = RecordingPeer()
        lateinit var listener: SignallingListener
        val signalling = SignallingTransportFactory { _, events ->
            listener = events
            object : SignallingTransport {
                override fun send(text: String) = true
                override fun close() = Unit
            }
        }
        val session = DeviceStreamSession(
            activity, { StreamCredentials("wss://stream.example.test", "test") }, backgroundScope,
            StreamConfig(), signalling, PeerTransportFactory { _, channels, events ->
                events.onConnectionState(PeerConnectionState.Connected)
                channels.forEach { events.onChannelState(it, true) }
                peer
            },
        )
        val view = DeviceStreamView(activity)
        try {
            session.start()
            runCurrent()
            listener.onOpen()
            runCurrent()
            listener.onMessage("""{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}""")
            runCurrent()
            listener.onMessage("""{"type":"answer","sdp":"answer"}""")
            runCurrent()
            assertEquals(StreamStatus.Connected, session.status.value)
            activity.setContentView(view, ViewGroup.LayoutParams(1000, 1000))
            view.attach(session)
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            view.layout(0, 0, 1000, 1000)
            // Supply decoded frame dimensions without loading a native decoder in a JVM test.
            view.updateGeometry(VideoSize(500, 1000, 0))
            assertTrue(view.requestFocus())
            assertTrue(view.isAttachedToWindow)
            peer.control.clear()
            block(view, peer, activity)
        } finally {
            view.detach()
            session.close()
            runCurrent()
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    private fun motion(
        view: DeviceStreamView,
        action: Int,
        points: List<Pair<Float, Float>> = listOf(500f to 200f),
        mouse: Boolean = false,
        flags: Int = 0,
        buttons: Int = if (mouse) MotionEvent.BUTTON_PRIMARY else 0,
        horizontal: Float = 0f,
        vertical: Float = 0f,
    ): Boolean {
        val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
            id = 7 + i
            toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
            x = px; y = py; pressure = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        } }.toTypedArray()
        val event = MotionEvent.obtain(0, 0, action, points.size, properties, coordinates,
            0, buttons, 1f, 1f, 0, 0,
            if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, flags)
        return try {
            when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> view.onHoverEvent(event)
                MotionEvent.ACTION_SCROLL -> view.dispatchGenericMotionEvent(event)
                else -> view.dispatchTouchEvent(event)
            }
        } finally { event.recycle() }
    }

    private fun touch(action: Int, slot: Long, x: Double, y: Double) =
        ControlMessages.touch(action, slot, 500, 1000, x, y, 1.0, 1, 1)

    @Test
    fun twoFingerCancelMouseHoverAndScrollReachTheCorrectTransportWithExactBytes() = withView { view, p, _ ->
        assertTrue(motion(view, MotionEvent.ACTION_DOWN))
        motion(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(500f to 200f, 600f to 300f))
        motion(view, MotionEvent.ACTION_MOVE, listOf(510f to 220f, 610f to 320f))
        motion(view, MotionEvent.ACTION_MOVE, listOf(520f to 240f, 620f to 340f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        motion(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(520f to 240f, 620f to 340f), flags = MotionEvent.FLAG_CANCELED)
        motion(view, MotionEvent.ACTION_UP, listOf(520f to 240f))
        p.control.assertSent(touch(0, 0, 250.0, 200.0), touch(0, 1, 350.0, 300.0),
            touch(2, 0, 270.0, 240.0), touch(2, 1, 370.0, 340.0),
            touch(3, 1, 370.0, 340.0), touch(1, 0, 270.0, 240.0))
        p.control.clear()
        motion(view, MotionEvent.ACTION_DOWN, mouse = true)
        motion(view, MotionEvent.ACTION_CANCEL, mouse = true)
        p.control.assertSent(touch(0, -1, 250.0, 200.0), touch(3, -1, 250.0, 200.0))
        p.control.clear()
        assertTrue(motion(view, MotionEvent.ACTION_HOVER_ENTER, mouse = true, buttons = 2))
        assertTrue(motion(view, MotionEvent.ACTION_HOVER_MOVE, mouse = true, buttons = 1))
        p.control.assertSent()
        p.hover.assertSent(ControlMessages.hover(500, 1000, 250.0, 200.0, 2),
            ControlMessages.hover(500, 1000, 250.0, 200.0, 1))
        motion(view, MotionEvent.ACTION_SCROLL, mouse = true, buttons = 0, horizontal = 1f, vertical = 1f)
        p.control.assertSent(ControlMessages.scroll(500, 1000, 250.0, 200.0, -1.0, 1.0))
        assertEquals(listOf(0, 8), p.control.single().slice(15..16).map { it.toInt() }) // 1 tick = 0x0800
    }

    @Test
    fun keyboardImeClipboardSystemKeysAndDisplayOnlyUseTheAttachedSession() = withView { view, p, activity ->
        fun key(action: Int, code: Int, meta: Int = 0): Boolean =
            view.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0, meta))
        assertTrue(key(0, KeyEvent.KEYCODE_A))
        key(1, KeyEvent.KEYCODE_A)
        key(0, KeyEvent.KEYCODE_NUMPAD_ENTER)
        key(1, KeyEvent.KEYCODE_NUMPAD_ENTER)
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
            KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON
        key(0, KeyEvent.KEYCODE_A, meta)
        key(1, KeyEvent.KEYCODE_A, meta)
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            assertFalse(view.onKeyDown(code, KeyEvent(0, 0, 0, code, 0, KeyEvent.META_CTRL_ON)))
        }
        assertFalse(view.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(0, KeyEvent.KEYCODE_BACK)))
        val info = EditorInfo()
        val input = assertNotNull(view.onCreateInputConnection(info))
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, info.inputType)
        input.setComposingText("hello", 1)
        input.finishComposingText()
        input.deleteSurroundingText(1, 0)
        input.performEditorAction(EditorInfo.IME_ACTION_GO)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "paste"))
        key(0, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        key(1, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        SystemKey.entries.forEach(view::sendSystemKey)
        p.control.assertSent(ControlMessages.text("a"),
            ControlMessages.keycode(0, 160), ControlMessages.keycode(1, 160),
            ControlMessages.keycode(0, 29, metaState = 4096),
            ControlMessages.keycode(1, 29, metaState = 4096),
            ControlMessages.text("hello"), ControlMessages.keycode(0, 67), ControlMessages.keycode(1, 67),
            ControlMessages.keycode(0, 66), ControlMessages.keycode(1, 66),
            ControlMessages.setClipboard("paste", true), ControlMessages.keycode(1, 50, metaState = 4096),
            ControlMessages.keycode(0, 4), ControlMessages.keycode(1, 4),
            ControlMessages.keycode(0, 3), ControlMessages.keycode(1, 3),
            ControlMessages.keycode(0, 187), ControlMessages.keycode(1, 187))
        p.control.clear()
        motion(view, MotionEvent.ACTION_DOWN)
        view.hasControl = false // cancel before switching off
        p.control.assertSent(touch(0, 0, 250.0, 200.0), touch(3, 0, 250.0, 200.0))
        p.control.clear()
        assertFalse(motion(view, MotionEvent.ACTION_DOWN))
        motion(view, MotionEvent.ACTION_HOVER_MOVE, mouse = true)
        motion(view, MotionEvent.ACTION_SCROLL, mouse = true, vertical = 1f)
        key(0, KeyEvent.KEYCODE_A)
        input.commitText("stale", 1)
        input.deleteSurroundingText(1, 0)
        input.performEditorAction(EditorInfo.IME_ACTION_DONE)
        SystemKey.entries.forEach(view::sendSystemKey)
        assertNull(view.onCreateInputConnection(EditorInfo()))
        p.control.assertSent()
        p.hover.assertSent()
    }

    @Test
    fun keyboardOwnershipAndFocusLossDoNotHideAnotherEditorsKeyboard() = withView { view, _, activity ->
        val other = View(activity).apply { isFocusableInTouchMode = true }
        val layout = LinearLayout(activity)
        activity.setContentView(layout)
        layout.addView(view)
        layout.addView(other)
        val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val shadow = Shadow.extract<OwningInputMethodManager>(manager)
        view.requestFocus()
        shadow.owner = view
        view.showKeyboard()
        assertTrue(shadow.isSoftInputVisible)
        other.requestFocus()
        shadow.owner = other
        assertTrue(shadow.isSoftInputVisible, "plain focus loss must not hide IME")
        view.hideKeyboard()
        assertTrue(shadow.isSoftInputVisible, "a different focused editor owns the IME")
        view.requestFocus()
        shadow.owner = view
        view.showKeyboard()
        view.hideKeyboard()
        assertFalse(shadow.isSoftInputVisible)
    }

    @Test
    fun noSessionHasNoVideoOrInputClientAndDetachIsIdempotent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = DeviceStreamView(controller.get())
        controller.get().setContentView(view)
        assertNull(view.renderedVideoRect)
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertFalse(view.onCheckIsTextEditor())
        view.showKeyboard()
        view.detach()
        view.detach()
        controller.pause().stop().destroy()
    }
}

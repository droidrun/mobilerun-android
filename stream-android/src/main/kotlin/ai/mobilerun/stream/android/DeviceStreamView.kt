// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.InputGeometry
import ai.mobilerun.stream.core.KeyInput
import ai.mobilerun.stream.core.PointerInput
import ai.mobilerun.stream.core.ScrollInput
import ai.mobilerun.stream.core.VideoSize
import ai.mobilerun.stream.protocol.ControlMessages
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/** An explicit Android system navigation action. The host's own Back event is never forwarded. */
public enum class SystemKey(internal val keycode: Int) {
    BACK(4), HOME(3), RECENT(187),
}

/**
 * Aspect-fit live device video with touch, mouse, wheel, hardware keyboard and IME input.
 *
 * Call [attach] with a session; the app still owns starting and closing that session. Removing this
 * view from the window releases its renderer and stops collecting media; reattaching restores it.
 * [detach] also forgets the session. All view methods and properties must be used on the main thread.
 * No placeholder, navigation chrome or implicit Back handling is installed.
 */
public class DeviceStreamView @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var session: DeviceStreamSession? = null
    private var renderer: SurfaceViewRenderer? = null
    private var attachedTrack: VideoTrack? = null
    private var mediaScope: CoroutineScope? = null
    private var mediaJob: Job? = null
    private var size: VideoSize? = null
    private var geometry: InputGeometry? = null
    private var inputGeneration = 0
    private var moveScheduled = false
    private val pointers = PointerInput(::sendControl)
    private val flushMoves = Runnable {
        moveScheduled = false
        if (hasControl) pointers.flushMoves() else pointers.reset()
    }

    /** Display-only when false. This is a local input switch; the server decides permissions. */
    public var hasControl: Boolean = true
        set(value) {
            if (field == value) return
            // Release an active gesture before relinquishing control; nothing is sent after false.
            if (!value) pointers.cancel()
            field = value
            invalidateInput()
            if (!value) hideKeyboard()
            inputMethodManager.restartInput(this)
        }

    /** Upright video's aspect-fit rectangle in this view, or null while no video is available. */
    public val renderedVideoRect: RectF?
        get() = geometry?.let { RectF(it.left.toFloat(), it.top.toFloat(),
            (it.left + it.width).toFloat(), (it.top + it.height).toFloat()) }

    private val inputMethodManager: InputMethodManager
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The renderer is not focusable: keyboard and IME input belong to the enclosing view.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    /** Displays [session] without starting or closing it. Repeated attachment is a no-op. */
    public fun attach(session: DeviceStreamSession) {
        if (this.session === session) return
        detach()
        this.session = session
        if (isAttachedToWindow) startRendering()
    }

    /** Stops rendering and releases local resources; the session keeps running. */
    public fun detach() {
        if (hasControl) pointers.cancel()
        hideKeyboard()
        stopRendering()
        session = null
        invalidateInput()
        inputMethodManager.restartInput(this)
    }

    /** Requests focus and opens the local soft keyboard when attached and controllable. */
    public fun showKeyboard() {
        if (!hasControl || session == null || !isAttachedToWindow) return
        if (requestFocus()) inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hides the local soft keyboard. */
    public fun hideKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Sends a complete Back, Home or Recents press when [hasControl] is true. */
    public fun sendSystemKey(key: SystemKey) {
        if (hasControl) KeyInput.press(key.keycode, ::sendControl)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRendering()
    }

    override fun onDetachedFromWindow() {
        if (hasControl) pointers.cancel()
        hideKeyboard()
        invalidateInput()
        stopRendering()
        super.onDetachedFromWindow()
    }

    private fun startRendering() {
        val current = session ?: return
        if (renderer != null) return
        val surface = SurfaceViewRenderer(context).apply {
            visibility = GONE
            init(current.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
        renderer = surface
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        mediaScope = scope
        mediaJob = scope.launch {
            combine(current.videoTrack, current.videoSize) { track, size -> track to size }
                .collect { (track, videoSize) ->
                    if (attachedTrack !== track) {
                        runCatching { attachedTrack?.removeSink(surface) }
                        attachedTrack = track
                        // A reconnect may release the old native track before the UI collector runs.
                        if (track != null) runCatching { track.addSink(surface) }
                        surface.clearImage()
                    }
                    updateGeometry(if (track == null) null else videoSize)
                    surface.visibility = if (track != null && geometry != null) VISIBLE else GONE
                }
        }
    }

    private fun stopRendering() {
        mediaJob?.cancel()
        mediaJob = null
        mediaScope?.cancel()
        mediaScope = null
        renderer?.let { surface ->
            runCatching { attachedTrack?.removeSink(surface) }
            surface.release()
            removeView(surface)
        }
        attachedTrack = null
        renderer = null
        updateGeometry(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry(size, force = true)
    }

    private fun updateGeometry(videoSize: VideoSize?, force: Boolean = false) {
        if (!force && size == videoSize) return
        if (hasControl) pointers.cancel() else pointers.reset()
        size = videoSize
        geometry = InputGeometry.fit(width, height, videoSize)
        geometry?.let { rect ->
            renderer?.layoutParams = LayoutParams(rect.width.roundToInt(), rect.height.roundToInt(), Gravity.CENTER)
        }
        renderer?.visibility = if (attachedTrack != null && geometry != null) VISIBLE else GONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasControl) return false
        val rect = geometry ?: return false
        val mouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        fun id(index: Int): Int = if (mouse) -1 else event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (mouse && event.buttonState and MotionEvent.BUTTON_PRIMARY == 0) return false
                val i = event.actionIndex
                if (pointers.down(id(i), event.getX(i).toDouble(), event.getY(i).toDouble(), rect, mouse)) {
                    requestFocus()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointers.move(id(i), event.getX(i).toDouble(), event.getY(i).toDouble())
                }
                if (!moveScheduled) {
                    moveScheduled = true
                    postOnAnimation(flushMoves)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointers.up(id(event.actionIndex))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.cancel()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            else -> return false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!hasControl) return super.onGenericMotionEvent(event)
        val rect = geometry ?: return super.onGenericMotionEvent(event)
        val point = rect.map(event.x.toDouble(), event.y.toDouble()) ?: return super.onGenericMotionEvent(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (event.buttonState != 0) return super.onGenericMotionEvent(event)
                session?.sendHover(ControlMessages.hover(rect.videoWidth, rect.videoHeight,
                    point.x, point.y, event.buttonState))
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                val ticks = ScrollInput.ticks(event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(), ScrollInput.Unit.ANDROID_AXIS)
                sendControl(ControlMessages.scroll(rect.videoWidth, rect.videoHeight, point.x, point.y,
                    ticks.horizontal, ticks.vertical, event.buttonState))
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }

    // Android dispatches hover through onHoverEvent rather than onGenericMotionEvent.
    override fun onHoverEvent(event: MotionEvent): Boolean =
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
            onGenericMotionEvent(event) else super.onHoverEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        routeKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        routeKey(event) || super.onKeyUp(keyCode, event)

    private fun routeKey(event: KeyEvent): Boolean {
        if (!hasControl || !hasFocus() || session == null ||
            event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP
        ) return false
        // Ctrl/Meta affect routing, but not the printable character used to recognise V/M shortcuts.
        val character = event.getUnicodeChar(event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv())
        val text = if (character > 0 && character and KeyCharacterMap.COMBINING_ACCENT == 0)
            String(Character.toChars(character)) else null
        return KeyInput.route(event.keyCode, text, event.action == KeyEvent.ACTION_DOWN, event.metaState,
            clipboard = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }, send = ::sendControl)
    }

    override fun onCheckIsTextEditor(): Boolean = hasControl && session != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!onCheckIsTextEditor()) return null
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        val generation = inputGeneration
        return StreamInputConnection(this,
            active = { hasControl && hasFocus() && isAttachedToWindow && session != null && generation == inputGeneration },
            send = ::sendControl, key = ::routeKey)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            if (hasControl) pointers.cancel()
            invalidateInput()
            hideKeyboard()
        }
    }

    private fun invalidateInput() {
        inputGeneration++
        pointers.reset()
        removeCallbacks(flushMoves)
        moveScheduled = false
    }

    private fun sendControl(bytes: ByteArray) {
        if (hasControl) session?.sendControl(bytes)
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.InputGeometry
import ai.mobilerun.stream.core.KeyInput
import ai.mobilerun.stream.core.PointerInput
import ai.mobilerun.stream.core.VideoSize
import ai.mobilerun.stream.protocol.AndroidKeys
import android.content.ClipboardManager
import android.os.Build
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/** An explicit Android system navigation action. The host's own Back event is never forwarded. */
public enum class SystemKey(internal val keycode: Int) {
    Back(AndroidKeys.KEYCODE_BACK), Home(AndroidKeys.KEYCODE_HOME), Recent(AndroidKeys.KEYCODE_APP_SWITCH),
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
    private var size: VideoSize? = null
    private var geometry: InputGeometry? = null
    private var inputGeneration = 0
    private var moveScheduled = false
    private val pointers = PointerInput(::sendControl)
    private val keys = KeyInput()
    private val clicks = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean = true
    })
    private val flushMoves = Runnable {
        moveScheduled = false
        pointers.flushMoves()
    }

    /** Display-only when false. This is a local input switch; the server decides permissions. */
    public var hasControl: Boolean = true
        set(value) {
            if (field == value) return
            // Release an active gesture before relinquishing control; nothing is sent after false.
            if (!value) releaseInput() else invalidateInput()
            field = value
            inputMethodManager.restartInput(this)
        }

    /** Upright video's aspect-fit rectangle in this view, or null while no video is available. */
    internal val renderedVideoRect: RectF?
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
        releaseInput()
        stopRendering()
        session = null
        inputMethodManager.restartInput(this)
    }

    /** Requests focus and opens the local soft keyboard when attached and controllable. */
    public fun showKeyboard() {
        if (!canSend || !isAttachedToWindow) return
        if (requestFocus()) inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hides the local soft keyboard. */
    public fun hideKeyboard() {
        if (inputMethodManager.isActive(this)) inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Sends a complete Back, Home or Recents press when [hasControl] is true. */
    public fun sendSystemKey(key: SystemKey) {
        KeyInput.press(key.keycode, ::sendControl)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRendering()
    }

    override fun onDetachedFromWindow() {
        releaseInput()
        stopRendering()
        super.onDetachedFromWindow()
    }

    private fun startRendering() {
        val current = session ?: return
        if (mediaScope != null) return
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        mediaScope = scope
        scope.launch {
            combine(current.videoTrack, current.videoSize) { track, size -> track to size }
                .collect { (track, videoSize) ->
                    if (attachedTrack !== track) {
                        renderer?.let { surface ->
                            runCatching { attachedTrack?.removeSink(surface) }
                            surface.clearImage()
                        }
                        attachedTrack = track
                        if (track != null) {
                            val surface = renderer ?: SurfaceViewRenderer(context).apply {
                                visibility = GONE
                                init(current.eglBaseContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                setEnableHardwareScaler(true)
                                renderer = this
                                addView(this, LayoutParams(
                                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                            }
                            // A reconnect may release the old native track before this collector runs.
                            runCatching { track.addSink(surface) }
                        }
                    }
                    updateGeometry(if (track == null) null else videoSize)
                }
        }
    }

    private fun showRendererIfReady() {
        renderer?.visibility = if (attachedTrack != null && geometry != null) VISIBLE else GONE
    }

    private fun stopRendering() {
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

    internal fun updateGeometry(videoSize: VideoSize?, force: Boolean = false) {
        if (!force && size == videoSize) return
        pointers.cancel()
        size = videoSize
        geometry = InputGeometry.fit(width, height, videoSize)
        showRendererIfReady()
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val rect = geometry ?: return
        val x = rect.left.roundToInt()
        val y = rect.top.roundToInt()
        renderer?.layout(x, y, x + rect.width.roundToInt(), y + rect.height.roundToInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canSend) return false
        val rect = geometry ?: return false
        val mouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        fun id(index: Int): Int = if (mouse) PointerInput.MOUSE_ID else event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (mouse && event.buttonState and MotionEvent.BUTTON_PRIMARY == 0) return false
                val i = event.actionIndex
                if (pointers.down(id(i), event.getX(i).toDouble(), event.getY(i).toDouble(), rect)) {
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
                val pointerId = id(event.actionIndex)
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) pointers.cancel(pointerId)
                else pointers.up(pointerId)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.cancel()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            else -> return false
        }
        if (clicks.onTouchEvent(event)) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (canSend) requestFocus()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!canSend || event.actionMasked != MotionEvent.ACTION_SCROLL) {
            return super.onGenericMotionEvent(event)
        }
        val rect = geometry ?: return super.onGenericMotionEvent(event)
        // Android axes are ticks; invert horizontal like React (positive means left on the wire).
        val bytes = rect.scroll(
            event.x.toDouble(), event.y.toDouble(),
            -event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
            event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(), event.buttonState,
        ) ?: return super.onGenericMotionEvent(event)
        sendControl(bytes)
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
            sendHover(event) || super.onHoverEvent(event)
        else -> super.onHoverEvent(event)
    }

    private fun sendHover(event: MotionEvent): Boolean {
        if (!canSend) return false
        val rect = geometry ?: return false
        if (pointers.isActive(PointerInput.MOUSE_ID)) return false
        val bytes = rect.hover(event.x.toDouble(), event.y.toDouble(), event.buttonState) ?: return false
        return session?.sendHover(bytes) == true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        routeKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        routeKey(event) || super.onKeyUp(keyCode, event)

    private fun routeKey(event: KeyEvent): Boolean {
        if (!acceptsKeys || event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        // Ctrl/Meta affect routing, but not the printable character used to recognise V/M shortcuts.
        val character = event.getUnicodeChar(event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv())
        val text = if (character > 0 && character and KeyCharacterMap.COMBINING_ACCENT == 0)
            String(Character.toChars(character)) else null
        var meta = 0
        if (event.isShiftPressed) meta = meta or AndroidKeys.META_SHIFT_ON
        if (event.isAltPressed) meta = meta or AndroidKeys.META_ALT_ON
        if (event.isCtrlPressed) meta = meta or AndroidKeys.META_CTRL_ON
        if (event.isMetaPressed) meta = meta or AndroidKeys.META_META_ON
        return keys.route(
            event.keyCode, text, event.action == KeyEvent.ACTION_DOWN, meta,
            clipboard = ::primaryClipText, send = ::sendControl, capsLock = event.isCapsLockOn,
        )
    }

    private val canSend: Boolean
        get() = hasControl && session?.isClosed == false

    private val acceptsKeys: Boolean
        get() = canSend && hasFocus() && isAttachedToWindow

    private fun primaryClipText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override fun onCheckIsTextEditor(): Boolean = canSend

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!onCheckIsTextEditor()) return null
        // Prefer per-character Latin commits; composition remains local until finalized.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        val generation = inputGeneration
        return StreamInputConnection(this,
            active = { acceptsKeys && generation == inputGeneration },
            send = ::sendControl, key = ::routeKey)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            pointers.cancel()
            invalidateInput()
        }
    }

    private fun releaseInput() {
        pointers.cancel()
        invalidateInput()
        hideKeyboard()
    }

    private fun invalidateInput() {
        keys.reset()
        inputGeneration++
        removeCallbacks(flushMoves)
        moveScheduled = false
    }

    private fun sendControl(bytes: ByteArray) {
        if (canSend) session?.sendControl(bytes)
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlin.math.max
import kotlin.math.min

/**
 * Encoders for the binary control messages sent over the `control` data channel
 * (and `hover` over the `hover` channel).
 *
 * Every function returns the exact bytes the `@mobilerun/react` package produces for the same
 * arguments. Byte order: the touch pointer id and the clipboard fields are big-endian, everything
 * else is little-endian. Arguments that the wire stores in a narrower type are truncated like a
 * JavaScript `DataView` would (two's complement wrap), never rejected, so a bad input from the
 * embedding app cannot crash an input handler.
 */
@InternalStreamApi
public object ControlMessages {
    private const val TOUCH_SIZE = 32
    private const val SCROLL_SIZE = 21
    private const val HOVER_SIZE = 17
    private const val KEYCODE_SIZE = 14
    private const val TEXT_HEADER_SIZE = 5
    private const val CLIPBOARD_HEADER_SIZE = 14

    /**
     * Touch (type 2, 32 bytes).
     *
     * @param action one of [AMotionEvent] `ACTION_*`.
     * @param pointerId pointer id; the mouse is `-1`. Written big-endian.
     * @param videoWidth width of the video frame the coordinates refer to, as `u16`.
     * @param videoHeight height of the video frame the coordinates refer to, as `u16`.
     * @param x horizontal position in video pixels, rounded half toward positive infinity.
     * @param y vertical position in video pixels, rounded half toward positive infinity.
     * @param pressure pressure in `0.0..1.0`; stored as `round(pressure * 0xffff)` in an `i16`,
     *   so `1.0` is `0xffff` (that is `-1` as `i16`).
     * @param actionButton button that changed, one of [AMotionEvent] `BUTTON_*` or 0.
     * @param buttons bitmask of pressed buttons ([AMotionEvent] `BUTTON_*`).
     */
    public fun touch(
        action: Int,
        pointerId: Long,
        videoWidth: Int,
        videoHeight: Int,
        x: Double,
        y: Double,
        pressure: Double = 1.0,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray {
        val out = ByteWriter(TOUCH_SIZE)
        out.u8(ControlMsgType.INJECT_TOUCH_EVENT)
        out.u8(action)
        out.i64Be(pointerId)
        out.position(x, y, videoWidth, videoHeight)
        out.i16Le(jsToInt16(jsRound(pressure * 0xffff)))
        out.i32Le(actionButton)
        out.i32Le(buttons)
        return out.bytes
    }

    /**
     * Scroll (type 3, 21 bytes).
     *
     * Ticks are wheel notches (`1.0` is one notch). Positive means up (vertical) or left
     * (horizontal). Each value is divided by 16, clamped to `[-1, 1]`, scaled by `0x8000` and
     * capped at `0x7fff`, as scrcpy does.
     *
     * @param videoWidth width of the video frame the coordinates refer to, as `u16`.
     * @param videoHeight height of the video frame the coordinates refer to, as `u16`.
     * @param x horizontal position in video pixels.
     * @param y vertical position in video pixels.
     * @param hscrollTicks horizontal wheel ticks.
     * @param vscrollTicks vertical wheel ticks.
     * @param buttons bitmask of pressed buttons ([AMotionEvent] `BUTTON_*`).
     */
    public fun scroll(
        videoWidth: Int,
        videoHeight: Int,
        x: Double,
        y: Double,
        hscrollTicks: Double,
        vscrollTicks: Double,
        buttons: Int = 0,
    ): ByteArray {
        val out = ByteWriter(SCROLL_SIZE)
        out.u8(ControlMsgType.INJECT_SCROLL_EVENT)
        out.position(x, y, videoWidth, videoHeight)
        out.i16Le(jsToInt16(scrollToFixedPoint(hscrollTicks / 16)))
        out.i16Le(jsToInt16(scrollToFixedPoint(vscrollTicks / 16)))
        out.i32Le(buttons)
        return out.bytes
    }

    /**
     * Mouse hover (type 21, 17 bytes). Sent on the `hover` channel; Android ignores it.
     *
     * @param videoWidth width of the video frame the coordinates refer to, as `u16`.
     * @param videoHeight height of the video frame the coordinates refer to, as `u16`.
     * @param x horizontal position in video pixels.
     * @param y vertical position in video pixels.
     * @param buttons bitmask of pressed buttons ([AMotionEvent] `BUTTON_*`).
     */
    public fun hover(
        videoWidth: Int,
        videoHeight: Int,
        x: Double,
        y: Double,
        buttons: Int = 0,
    ): ByteArray {
        val out = ByteWriter(HOVER_SIZE)
        out.u8(ControlMsgType.INJECT_MOUSE_HOVER_EVENT)
        out.position(x, y, videoWidth, videoHeight)
        out.i32Le(buttons)
        return out.bytes
    }

    /**
     * Key event (type 0, 14 bytes).
     *
     * @param action [AndroidKeys.ACTION_DOWN] or [AndroidKeys.ACTION_UP].
     * @param keycode an Android key code, see [AndroidKeys] and [KeyboardCodes].
     * @param repeat repeat count.
     * @param metaState bitmask of [AndroidKeys] `META_*` flags.
     */
    public fun keycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ): ByteArray {
        val out = ByteWriter(KEYCODE_SIZE)
        out.u8(ControlMsgType.INJECT_KEYCODE)
        out.u8(action)
        out.i32Le(keycode)
        out.i32Le(repeat)
        out.i32Le(metaState)
        return out.bytes
    }

    /**
     * Text injection (type 1, `5 + n` bytes). The string is sent as UTF-8 without normalisation
     * (an unpaired surrogate becomes U+FFFD, like `TextEncoder`); the length prefix is the byte
     * count, little-endian.
     *
     * @param text the text to type on the device.
     */
    public fun text(text: String): ByteArray {
        val payload = text.encodeUtf8()
        val out = ByteWriter(TEXT_HEADER_SIZE + payload.size)
        out.u8(ControlMsgType.INJECT_TEXT)
        out.i32Le(payload.size)
        out.raw(payload)
        return out.bytes
    }

    /**
     * Set clipboard (type 9, `14 + n` bytes). The acknowledgement sequence is always 0 and the
     * length is a big-endian byte count.
     *
     * @param text clipboard content, sent as UTF-8 (an unpaired surrogate becomes U+FFFD).
     * @param paste whether the device should paste the content right after setting it.
     */
    public fun setClipboard(text: String, paste: Boolean = true): ByteArray {
        val payload = text.encodeUtf8()
        val out = ByteWriter(CLIPBOARD_HEADER_SIZE + payload.size)
        out.u8(ControlMsgType.SET_CLIPBOARD)
        out.i64Be(0L)
        out.u8(if (paste) 1 else 0)
        out.i32Be(payload.size)
        out.raw(payload)
        return out.bytes
    }

    /** Mirrors `sc_float_to_i16fp`: clamp to `[-1, 1]`, scale by `0x8000`, round, cap at `0x7fff`. */
    private fun scrollToFixedPoint(value: Double): Double {
        val clamped = max(-1.0, min(1.0, value))
        return min(jsRound(clamped * 0x8000), 0x7fff.toDouble())
    }
}

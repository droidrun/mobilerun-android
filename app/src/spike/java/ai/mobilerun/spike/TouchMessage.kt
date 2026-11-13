package ai.mobilerun.spike

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte-for-byte port of createTouchControlMessage (React): 32 bytes, pointerId big-endian, rest little-endian. */
object TouchMessage {
    const val INJECT_TOUCH_EVENT = 2
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val BUTTON_PRIMARY = 1

    fun create(
        action: Int,
        pointerId: Long,
        videoWidth: Int,
        videoHeight: Int,
        x: Double,
        y: Double,
        pressure: Double = 1.0,
        actionButton: Int = BUTTON_PRIMARY,
        buttons: Int = BUTTON_PRIMARY,
    ): ByteArray {
        val b = ByteBuffer.allocate(32)
        b.put(INJECT_TOUCH_EVENT.toByte())
        b.put(action.toByte())
        b.order(ByteOrder.BIG_ENDIAN).putLong(pointerId)
        b.order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(Math.round(x).toInt())
        b.putInt(Math.round(y).toInt())
        b.putShort(videoWidth.toShort())
        b.putShort(videoHeight.toShort())
        b.putShort(Math.round(pressure * 0xffff).toInt().toShort())
        b.putInt(actionButton)
        b.putInt(buttons)
        return b.array()
    }

    /**
     * Letterbox mapping (use-scrcpy-input.ts computeVideoCoords). The renderer reports the
     * pre-rotation frame size, so swap for 90/270 before mapping. Returns null outside the video.
     */
    fun mapToVideo(
        viewX: Float, viewY: Float, viewW: Int, viewH: Int,
        frameW: Int, frameH: Int, rotation: Int,
    ): Triple<Double, Double, Pair<Int, Int>>? {
        val (vw, vh) = if (rotation == 90 || rotation == 270) frameH to frameW else frameW to frameH
        if (vw == 0 || vh == 0 || viewW == 0 || viewH == 0) return null
        val videoAr = vw.toDouble() / vh
        val containerAr = viewW.toDouble() / viewH
        var aw = viewW.toDouble()
        var ah = viewH.toDouble()
        if (videoAr > containerAr) ah = viewW / videoAr else aw = viewH * videoAr
        val ox = (viewW - aw) / 2
        val oy = (viewH - ah) / 2
        val rx = viewX - ox
        val ry = viewY - oy
        if (rx < 0 || rx > aw || ry < 0 || ry > ah) return null
        return Triple((rx / aw * vw).coerceIn(0.0, vw.toDouble()), (ry / ah * vh).coerceIn(0.0, vh.toDouble()), vw to vh)
    }
}

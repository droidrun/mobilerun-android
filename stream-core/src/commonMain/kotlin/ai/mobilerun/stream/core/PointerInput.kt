// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AMotionEvent
import ai.mobilerun.stream.protocol.ControlMessages

/** Stable touch slots and frame-coalesced movement. Calls must be serial, normally on the UI thread. */
@InternalStreamApi
public class PointerInput(private val send: (ByteArray) -> Unit) {
    private class Pointer(val slot: Long, var point: InputPoint, val geometry: InputGeometry) {
        var pendingMove = false
    }

    private val active = mutableMapOf<Int, Pointer>()

    /** Allocates the lowest free slot in 0..9; the mouse always uses -1. */
    public fun down(id: Int, x: Double, y: Double, geometry: InputGeometry): Boolean {
        if (id in active) return false
        val point = geometry.map(x, y) ?: return false
        val slot = if (id == MOUSE_ID) {
            MOUSE_ID.toLong()
        } else {
            (0L..9L).firstOrNull { slot -> active.values.none { it.slot == slot } } ?: return false
        }
        val pointer = Pointer(slot, point, geometry)
        active[id] = pointer
        emit(AMotionEvent.ACTION_DOWN, pointer)
        return true
    }

    /** Outside moves are ignored; UP/CANCEL still releases at the last position inside. */
    public fun move(id: Int, x: Double, y: Double) {
        val pointer = active[id] ?: return
        pointer.point = pointer.geometry.map(x, y) ?: return
        pointer.pendingMove = true
    }

    /** Sends at most one MOVE per active pointer since the preceding frame. */
    public fun flushMoves() {
        active.values.forEach { pointer ->
            if (pointer.pendingMove) {
                pointer.pendingMove = false
                emit(AMotionEvent.ACTION_MOVE, pointer)
            }
        }
    }

    /** Releases at the latest in-video point, including movement waiting for the next frame. */
    public fun up(id: Int): Unit = release(id, AMotionEvent.ACTION_UP)

    /** Cancels one pointer, including a platform-cancelled POINTER_UP. */
    public fun cancel(id: Int): Unit = release(id, AMotionEvent.ACTION_CANCEL)

    private fun release(id: Int, action: Int) {
        val pointer = active.remove(id) ?: return
        emit(action, pointer)
    }

    /** Ends all gestures before detaching or changing video geometry. */
    public fun cancel() {
        active.keys.toList().forEach(::cancel)
    }

    public companion object {
        /** Mouse pointer id on every input channel. */
        public const val MOUSE_ID: Int = -1
    }

    private fun emit(action: Int, pointer: Pointer) {
        send(ControlMessages.touch(
            action, pointer.slot, pointer.geometry.videoWidth, pointer.geometry.videoHeight,
            pointer.point.x, pointer.point.y, 1.0,
            AMotionEvent.BUTTON_PRIMARY, AMotionEvent.BUTTON_PRIMARY,
        ))
    }
}

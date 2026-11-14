// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerInputTest {
    private val messages = mutableListOf<ByteArray>()
    private val input = PointerInput { messages += it }
    private val geometry = InputGeometry.fit(1000, 1000, VideoSize(500, 1000, 0))!!
    private fun actions() = messages.map { it[1].toInt() }
    private fun slots() = messages.map { it[9].toInt() }
    private fun ByteArray.intLe(offset: Int) = (0..3).fold(0) { value, i ->
        value or ((this[offset + i].toInt() and 255) shl (i * 8))
    }

    @Test
    fun downOutsideVideoNeverStartsAGesture() {
        assertFalse(input.down(5, 100.0, 200.0, geometry))
        input.move(5, 500.0, 200.0)
        input.up(5)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun multiTouchUsesStableSmallSlotsAndIndividualActions() {
        input.down(42, 300.0, 200.0, geometry)
        input.down(999, 500.0, 300.0, geometry)
        input.move(999, 600.0, 400.0)
        input.flushMoves()
        input.up(42)
        input.down(123, 500.0, 200.0, geometry)
        input.up(999)
        input.up(123)
        assertEquals(listOf(0, 0, 2, 1, 0, 1, 1), actions())
        assertEquals(listOf(0, 1, 1, 0, 0, 1, 0), slots())
        messages.forEach {
            assertEquals(32, it.size)
            assertEquals(255, it[22].toInt() and 255) // pressure = 1.0 for every action
            assertEquals(255, it[23].toInt() and 255)
            assertEquals(1, it.intLe(24))
            assertEquals(1, it.intLe(28))
        }
    }

    @Test
    fun movesCoalescePerFingerAndReleaseUsesLastInsidePosition() {
        input.down(0, 300.0, 200.0, geometry)
        input.move(0, 400.0, 250.0)
        input.move(0, 450.0, 300.0)
        input.flushMoves()
        input.flushMoves()
        input.move(0, 800.0, 400.0) // letterbox; ignored
        input.up(0)
        assertEquals(listOf(0, 2, 1), actions())
        assertEquals(200, messages.last().intLe(10))
        assertEquals(300, messages.last().intLe(14))
    }

    @Test
    fun releaseBeforeNextFrameUsesTheLastPointWithoutAnExtraMove() {
        input.down(7, 500.0, 100.0, geometry)
        input.move(7, 500.0, 900.0)
        input.up(7)
        input.flushMoves()
        assertEquals(listOf(0, 1), actions())
        assertEquals(900, messages.last().intLe(14))
    }

    @Test
    fun cancelReleasesAllPointersAndPendingMoves() {
        input.down(42, 500.0, 200.0, geometry)
        input.down(43, 500.0, 300.0, geometry)
        input.cancel()
        assertEquals(listOf(0, 0, 3, 3), actions())
        val count = messages.size
        input.up(42)
        input.flushMoves()
        assertEquals(count, messages.size)
    }

    @Test
    fun tenFingersMaximumWithMouseSeparate() {
        for (id in 10..19) assertTrue(input.down(id, 500.0, 200.0, geometry))
        assertFalse(input.down(20, 500.0, 200.0, geometry))
        assertFalse(input.down(10, 500.0, 200.0, geometry))
        assertTrue(input.down(-1, 500.0, 200.0, geometry))
        assertEquals(-1, slots().last())
        assertTrue(messages.last().sliceArray(2..9).all { it == (-1).toByte() })
        input.up(13)
        assertTrue(input.down(20, 500.0, 200.0, geometry))
        assertEquals(3, slots().last())
    }
}

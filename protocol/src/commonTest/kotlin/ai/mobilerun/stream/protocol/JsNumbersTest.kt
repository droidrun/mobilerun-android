// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** The JavaScript number semantics the encoders depend on. */
class JsNumbersTest {
    @Test
    fun roundHalvesTowardPositiveInfinity() {
        assertEquals(3.0, jsRound(2.5))
        assertEquals(0.0, jsRound(-0.5))
        assertEquals(-1.0, jsRound(-1.5))
        assertEquals(0.0, jsRound(0.49999999999999994))
        assertEquals(4503599627370497.0, jsRound(4503599627370497.0))
    }

    @Test
    fun toInt32WrapsLikeEcmaScript() {
        assertEquals(0, jsToInt32(Double.NaN))
        assertEquals(0, jsToInt32(Double.POSITIVE_INFINITY))
        assertEquals(Int.MIN_VALUE, jsToInt32(2147483648.0))
        assertEquals(-1, jsToInt32(4294967295.0))
        assertEquals(1, jsToInt32(4294967297.0))
        assertEquals(-5, jsToInt32(-5.9))
        assertEquals(1661992960, jsToInt32(1e20))
    }

    @Test
    fun toInt16WrapsLikeDataView() {
        assertEquals(-1, jsToInt16(65535.0))
        assertEquals(32767, jsToInt16(32767.0))
        assertEquals(-32768, jsToInt16(32768.0))
    }

    @Test
    fun nonFiniteScrollAndCoordinatesDoNotThrow() {
        ControlMessages.scroll(1080, 2400, Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, Double.NEGATIVE_INFINITY)
        ControlMessages.touch(0, 0, 1080, 2400, Double.NaN, Double.NaN, pressure = Double.NaN)
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlin.math.floor
import kotlin.math.truncate

// The few JavaScript number conversions the reference encoders rely on
// (`Math.round`, and the implicit wrap of `DataView.setInt32`/`setInt16`).
// Kept bit-exact so every SDK emits the same bytes for the same input.

/** `Math.round`: halves round toward positive infinity (`2.5` to `3`, `-0.5` to `0`, `-1.5` to `-1`). */
internal fun jsRound(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) return value
    val lower = floor(value)
    return if (value - lower >= 0.5) lower + 1.0 else lower
}

/** ECMAScript `ToInt32`: truncate, wrap modulo 2^32; NaN and infinities become 0. */
internal fun jsToInt32(value: Double): Int {
    if (value.isNaN() || value.isInfinite()) return 0
    return (truncate(value) % 4294967296.0).toLong().toInt()
}

/** ECMAScript `ToInt16` as used by `DataView.setInt16`. */
internal fun jsToInt16(value: Double): Int = jsToInt32(value).toShort().toInt()

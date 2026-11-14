// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** Wheel deltas converted to the ticks accepted by the protocol encoder (which applies /16). */
@InternalStreamApi
public object ScrollInput {
    public enum class Unit { PIXEL, LINE, PAGE, ANDROID_AXIS }

    public data class Ticks(public val horizontal: Double, public val vertical: Double)

    /** Android axes are already ticks: positive vertical is up, positive horizontal is right. */
    public fun ticks(horizontal: Double, vertical: Double, unit: Unit): Ticks = when (unit) {
        Unit.PIXEL -> Ticks(-horizontal / 100, -vertical / 100)
        Unit.LINE -> Ticks(-horizontal, -vertical)
        Unit.PAGE -> Ticks(-horizontal * 3, -vertical * 3)
        Unit.ANDROID_AXIS -> Ticks(-horizontal, vertical)
    }
}

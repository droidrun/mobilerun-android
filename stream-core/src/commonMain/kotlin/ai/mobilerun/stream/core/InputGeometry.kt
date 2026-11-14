// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.math.min

/** Aspect-fit geometry in view coordinates, using the upright video dimensions. */
@InternalStreamApi
public class InputGeometry private constructor(
    public val left: Double,
    public val top: Double,
    public val width: Double,
    public val height: Double,
    public val videoWidth: Int,
    public val videoHeight: Int,
) {
    /** Returns null in the letterbox or for invalid coordinates. Edges are inclusive, as in React. */
    public fun map(x: Double, y: Double): InputPoint? {
        val relativeX = x - left
        val relativeY = y - top
        if (!x.isFinite() || !y.isFinite() || relativeX < 0 || relativeY < 0 ||
            relativeX > width || relativeY > height
        ) return null
        return InputPoint(relativeX / width * videoWidth, relativeY / height * videoHeight)
    }

    @InternalStreamApi
    public companion object {
        /** No geometry until both the view and the decoded video have nonzero dimensions. */
        public fun fit(viewWidth: Int, viewHeight: Int, video: VideoSize?): InputGeometry? {
            if (viewWidth <= 0 || viewHeight <= 0 || video == null ||
                video.displayWidth <= 0 || video.displayHeight <= 0
            ) return null
            val scale = min(viewWidth.toDouble() / video.displayWidth, viewHeight.toDouble() / video.displayHeight)
            val width = video.displayWidth * scale
            val height = video.displayHeight * scale
            return InputGeometry(
                (viewWidth - width) / 2, (viewHeight - height) / 2,
                width, height, video.displayWidth, video.displayHeight,
            )
        }
    }
}

/** Position in upright video pixels. */
@InternalStreamApi
public data class InputPoint(public val x: Double, public val y: Double)

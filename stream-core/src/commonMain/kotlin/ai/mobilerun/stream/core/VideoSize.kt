// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.InternalStreamApi

/**
 * Geometry of the received video. Equality is hand-written and this is no data class: public
 * session types must be able to gain fields without breaking callers.
 *
 * @property width width of the decoded frame in pixels, before [rotation] is applied.
 * @property height height of the decoded frame in pixels, before [rotation] is applied.
 * @property rotation clockwise rotation in degrees (0, 90, 180 or 270) a renderer must apply to
 *   show the frame upright.
 */
public class VideoSize @InternalStreamApi public constructor(public val width: Int, public val height: Int, public val rotation: Int) {
    /** Width of the upright picture: [height] for a 90 or 270 degree rotation, else [width]. */
    public val displayWidth: Int get() = if (rotation % 180 == 0) width else height

    /** Height of the upright picture: [width] for a 90 or 270 degree rotation, else [height]. */
    public val displayHeight: Int get() = if (rotation % 180 == 0) height else width

    override fun equals(other: Any?): Boolean =
        other is VideoSize && other.width == width && other.height == height && other.rotation == rotation

    override fun hashCode(): Int = (31 * width + height) * 31 + rotation

    override fun toString(): String = "VideoSize(${width}x$height, rotation=$rotation)"
}

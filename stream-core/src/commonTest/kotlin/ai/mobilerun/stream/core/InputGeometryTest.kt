// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InputGeometryTest {
    @Test
    fun portraitLetterboxIgnoresOutsideAndIncludesEdges() {
        val rect = assertNotNull(InputGeometry.fit(1000, 1000, VideoSize(500, 1000, 0)))
        assertEquals(250.0, rect.left)
        assertEquals(InputPoint(250.0, 500.0), rect.map(500.0, 500.0))
        assertEquals(InputPoint(0.0, 0.0), rect.map(250.0, 0.0))
        assertEquals(InputPoint(500.0, 1000.0), rect.map(750.0, 1000.0))
        assertNull(rect.map(249.0, 500.0))
        assertNull(rect.map(751.0, 500.0))
        assertNull(rect.map(Double.NaN, 500.0))
    }

    @Test
    fun landscapeHasTopAndBottomLetterbox() {
        val rect = assertNotNull(InputGeometry.fit(1000, 1000, VideoSize(1920, 1080, 0)))
        assertEquals(218.75, rect.top)
        assertEquals(InputPoint(960.0, 540.0), rect.map(500.0, 500.0))
        assertNull(rect.map(500.0, 218.0))
        assertNull(rect.map(500.0, 782.0))
    }

    @Test
    fun rotationUsesDisplayedDimensionsWithoutRotatingCoordinatesAgain() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val video = VideoSize(1080, 1920, rotation)
            val rect = assertNotNull(InputGeometry.fit(1000, 800, video))
            val expectedWidth = if (rotation % 180 == 0) 1080 else 1920
            val expectedHeight = if (rotation % 180 == 0) 1920 else 1080
            assertEquals(expectedWidth, rect.videoWidth, "rotation $rotation")
            assertEquals(expectedHeight, rect.videoHeight)
            assertEquals(InputPoint(expectedWidth / 4.0, expectedHeight / 4.0),
                rect.map(rect.left + rect.width / 4, rect.top + rect.height / 4))
        }
    }

    @Test
    fun noMappingUntilVideoAndViewAreSized() {
        assertNull(InputGeometry.fit(0, 100, VideoSize(100, 100, 0)))
        assertNull(InputGeometry.fit(100, 0, VideoSize(100, 100, 0)))
        assertNull(InputGeometry.fit(100, 100, null))
        assertNull(InputGeometry.fit(100, 100, VideoSize(0, 100, 0)))
    }
}

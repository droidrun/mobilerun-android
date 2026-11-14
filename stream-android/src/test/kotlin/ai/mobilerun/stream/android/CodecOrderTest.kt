// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodecOrderTest {
    // The receiver capabilities of the emulator used in development, in the order libwebrtc lists them.
    private val withH265 = listOf(
        "video/VP8", "video/rtx", "video/VP9", "video/AV1", "video/H264", "video/H265", "video/red", "video/ulpfec",
    )
    private val withoutH265 = withH265 - "video/H265"

    private fun order(codecs: List<String>, preference: VideoCodecPreference) =
        CodecOrder.order(codecs, { it }, preference)

    @Test
    fun autoUsesTheBrowserOrderWhenH265IsAvailable() {
        assertEquals(
            listOf("video/VP9", "video/H265", "video/H264", "video/VP8", "video/rtx", "video/AV1", "video/red", "video/ulpfec"),
            order(withH265, VideoCodecPreference.Auto),
        )
    }

    @Test
    fun autoFallsBackToH264OnlyWithoutAnH265Decoder() {
        assertEquals(
            listOf("video/rtx", "video/H264", "video/red", "video/ulpfec"),
            order(withoutH265, VideoCodecPreference.Auto),
        )
    }

    @Test
    fun h264OnlyKeepsOnlyH264EvenWhenH265IsAvailable() {
        assertEquals(
            listOf("video/rtx", "video/H264", "video/red", "video/ulpfec"),
            order(withH265, VideoCodecPreference.H264Only),
        )
    }

    @Test
    fun nothingIsReturnedWhenNoRealCodecRemains() {
        assertNull(order(listOf("video/VP8", "video/rtx"), VideoCodecPreference.H264Only))
        assertNull(order(emptyList(), VideoCodecPreference.Auto))
    }

    @Test
    fun matchesAvcAndHevcAliasesCaseInsensitively() {
        assertEquals(
            listOf("video/hevc", "video/avc", "video/VP8"),
            order(listOf("video/VP8", "video/avc", "video/hevc"), VideoCodecPreference.Auto),
        )
    }
}

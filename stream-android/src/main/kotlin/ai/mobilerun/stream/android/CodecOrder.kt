// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

internal object CodecOrder {
    private fun String.isH265() = contains("h265", ignoreCase = true) || contains("hevc", ignoreCase = true)

    private fun String.isH264() = contains("h264", ignoreCase = true) || contains("avc", ignoreCase = true)

    private fun String.isVp9() = contains("vp9", ignoreCase = true)

    // Retransmission and redundancy entries carry no picture; they accompany whichever codecs stay.
    private fun String.isAuxiliary() =
        contains("rtx", ignoreCase = true) || contains("red", ignoreCase = true) ||
            contains("ulpfec", ignoreCase = true) || contains("flexfec", ignoreCase = true)

    /**
     * The receiver codec preference list, or `null` if nothing sensible can be set (the defaults then stay).
     *
     * [VideoCodecPreference.Auto] is the browser client's order, VP9, H.265, H.264, rest (stable
     * within a class); if [codecs] holds no H.265 the receiver cannot decode it and only H.264 is kept.
     */
    fun <T> order(codecs: List<T>, mimeType: (T) -> String, preference: VideoCodecPreference): List<T>? {
        val hasH265 = codecs.any { mimeType(it).isH265() }
        val h264Only = preference == VideoCodecPreference.H264Only ||
            (preference == VideoCodecPreference.Auto && !hasH265)
        val ordered = if (h264Only) {
            codecs.filter { mimeType(it).isH264() || mimeType(it).isAuxiliary() }
        } else {
            codecs.sortedBy {
                val mime = mimeType(it)
                when {
                    mime.isVp9() -> 1
                    mime.isH265() -> 2
                    mime.isH264() -> 3
                    else -> 4
                }
            }
        }
        // setCodecPreferences rejects a list without any real codec.
        return ordered.takeIf { list -> list.any { !mimeType(it).isAuxiliary() } }
    }
}

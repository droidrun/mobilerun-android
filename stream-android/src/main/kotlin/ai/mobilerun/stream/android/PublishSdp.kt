// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.PublishKind

/** Verify, never rewrite, the native trackless offer. Absence means headless start is unsupported. */
internal fun validPublishReservations(sdp: String, mids: Map<PublishKind, String?>): Set<PublishKind> {
    val sections = sdp.replace("\r\n", "\n").split("\nm=").drop(1)
    return mids.filter { (kind, mid) ->
        val section = sections.firstOrNull { mid != null && "a=mid:$mid" in it.lines() } ?: return@filter false
        val lines = section.lines()
        "a=sendonly" in lines && lines.any { it.startsWith("a=ssrc:") } && when (kind) {
            PublishKind.Audio -> lines.any { it.matches(Regex("a=rtpmap:[0-9]+ opus/48000/2", RegexOption.IGNORE_CASE)) }
            PublishKind.Video -> lines.filter { it.matches(Regex("a=rtpmap:[0-9]+ H264/90000", RegexOption.IGNORE_CASE)) }
                .any { mapping ->
                    val payload = mapping.substringAfter(':').substringBefore(' ')
                    val fmtp = lines.firstOrNull { it.startsWith("a=fmtp:$payload ") }
                        ?.substringAfter(' ')?.split(';')?.map { it.trim().lowercase() }.orEmpty()
                    "packetization-mode=1" in fmtp && fmtp.any { it.startsWith("profile-level-id=") &&
                        supportsPublishH264(it.substringAfter('=')) }
                }
        }
    }.keys.toSet()
}

/** Contract profiles with the platform's original level preserved (minimum level 3.1). */
internal val PUBLISH_H264_PROFILE_PREFIXES: Set<String> = setOf("42e0", "42c0", "4d00", "640c")

internal fun supportsPublishH264(profileLevelId: String?): Boolean {
    val value = profileLevelId?.lowercase() ?: return false
    return value.length == 6 && value.take(4) in PUBLISH_H264_PROFILE_PREFIXES &&
        (value.takeLast(2).toIntOrNull(16) ?: -1) >= 0x1f
}

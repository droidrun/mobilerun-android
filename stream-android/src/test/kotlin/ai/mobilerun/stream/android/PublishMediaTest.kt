// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.PublishException
import ai.mobilerun.stream.core.PublishKind
import ai.mobilerun.stream.core.PublishOptions
import ai.mobilerun.stream.core.StreamErrorReason
import android.Manifest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublishMediaTest {
    @Test fun audioOnlyNeedsNoCameraPermission() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
        checkPermissions(context, PublishOptions(true))
        val denied = assertFailsWith<PublishException> { checkPermissions(context, PublishOptions(true, true)) }
        assertEquals(StreamErrorReason.PermissionDenied, denied.failure.reason)
        assertEquals("capture_permission", denied.failure.detail)
    }

    @Test fun missingMicrophonePermissionFailsBeforeOpeningInputs() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(StreamErrorReason.PermissionDenied,
            assertFailsWith<PublishException> { checkPermissions(context, PublishOptions(true, true)) }.failure.reason)
    }

    private val offer = """
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=mid:0
        a=recvonly
        m=video 9 UDP/TLS/RTP/SAVPF 102
        a=mid:1
        a=recvonly
        m=video 9 UDP/TLS/RTP/SAVPF 102
        a=mid:2
        a=sendonly
        a=ssrc:123 cname:publish
        a=rtpmap:102 H264/90000
        a=fmtp:102 packetization-mode=1;profile-level-id=42e01f
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=mid:3
        a=sendonly
        a=ssrc:456 cname:publish
        a=rtpmap:111 opus/48000/2
    """.trimIndent()

    @Test fun onlySsrcBearingTracklessSendersAreSupported() {
        val mids = mapOf(PublishKind.Video to "2", PublishKind.Audio to "3")
        assertEquals(setOf(PublishKind.Audio, PublishKind.Video), validPublishReservations(offer, mids))
        assertEquals(setOf(PublishKind.Video), validPublishReservations(offer.replace("a=ssrc:456 cname:publish", ""), mids))
        assertEquals(emptySet(), validPublishReservations(offer.replace("a=sendonly", "a=inactive"), mids))
        assertEquals(setOf(PublishKind.Video), validPublishReservations(offer.replace("opus/48000/2", "opus/48000/1"), mids))
    }
    @Test fun h264ProfileRuleAcceptsAllContractPrefixesAtOrAboveLevel31() {
        listOf("42e01f", "42e02a", "42c020", "4d0033", "640c34", "42E02A").forEach {
            assertTrue(supportsPublishH264(it), it)
        }
        listOf(null, "42e01e", "42c000", "4d0010", "640c1a", "64001f", "42e0zz", "42e01fff").forEach {
            assertFalse(supportsPublishH264(it), it)
        }
        val mids = mapOf(PublishKind.Video to "2")
        assertEquals(setOf(PublishKind.Video), validPublishReservations(offer.replace("42e01f", "42e02a"), mids))
        assertEquals(emptySet(), validPublishReservations(offer.replace("42e01f", "42e01e"), mids))
    }

}

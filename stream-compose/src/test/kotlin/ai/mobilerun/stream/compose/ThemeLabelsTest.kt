// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.StreamStatus
import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals

class ThemeLabelsTest {
    @Test fun reactLightTokens() {
        val t = StreamThemeTokens.light()
        assertEquals(listOf(0xFFFFFFFF, 0xFF0A0A0A, 0xFFFFFFFF, 0xFF0A0A0A, 0xFFF5F5F5, 0xFF737373,
            0xFFE5E5E5, 0xFFA1A1A1, 0xFF171717, 0xFFFAFAFA, 0xFFF5F5F5, 0xFF171717, 0xFFE7000B).map(::Color),
            listOf(t.background, t.foreground, t.card, t.cardForeground, t.muted, t.mutedForeground,
                t.border, t.ring, t.primary, t.primaryForeground, t.accent, t.accentForeground, t.destructive))
    }
    @Test fun reactDarkTokens() {
        val t = StreamThemeTokens.dark()
        assertEquals(listOf(0xFF0A0A0A, 0xFFFAFAFA, 0xFF171717, 0xFFFAFAFA, 0xFF262626, 0xFFA1A1A1,
            0xFF737373, 0xFFFAFAFA, 0xFF171717, 0xFF262626, 0xFFFAFAFA, 0xFFFF6467).map(::Color),
            listOf(t.background, t.foreground, t.card, t.cardForeground, t.muted, t.mutedForeground,
                t.ring, t.primary, t.primaryForeground, t.accent, t.accentForeground, t.destructive))
        assertEquals(Color.White.copy(alpha = .1f), t.border)
        assertEquals(Color.Red, t.copy(primary = Color.Red).primary)
    }
    @Test fun reconnectTakesPrecedenceOverDeviceState() {
        val labels = StreamLabels()
        assertEquals("starting", labels.copy(connecting = "starting").statusLabel(StreamStatus.Connecting))
        assertEquals("connecting", labels.statusLabel(StreamStatus.Connecting))
        assertEquals("reconnecting", labels.statusLabel(StreamStatus.Reconnecting(1)))
        assertEquals("reconnecting (2/6)", labels.statusLabel(StreamStatus.Reconnecting(2)))
        assertEquals("Recover 3", labels.copy(reconnectingAttempt = "Recover {attempt}").statusLabel(StreamStatus.Reconnecting(3)))
    }
    @Test fun approvalEnglishDefaultsMatchReact() {
        val t = TaskApprovalLabels()
        assertEquals(listOf("Approve payment", "Waiting for approval", "Card", "App", "Domain", "{time} left", "Approval expired",
            "Approve", "Deny", "Approval view", "Details", "Device"),
            listOf(t.paymentCardTitle, t.waiting, t.card, t.app, t.domain, t.timeLeft, t.expired, t.approve, t.deny, t.viewSwitch, t.details, t.device))
    }
}

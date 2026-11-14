// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.ReconnectPolicy
import ai.mobilerun.stream.core.StreamStatus
import androidx.compose.runtime.Immutable

/** All stream chrome strings. Placeholders use {attempt} and {max}; caller labels are never translated. */
@Immutable
public data class StreamLabels(
    public val connecting: String = "connecting",
    public val reconnecting: String = "reconnecting",
    public val reconnectingAttempt: String = "reconnecting ({attempt}/{max})",
    public val unavailable: String = "device stream unavailable",
    public val retry: String = "Retry",
    public val back: String = "Back",
    public val home: String = "Home",
    public val recent: String = "Recent",
)

/** Exactly the keys and English defaults of React's TaskApprovalLabels. */
@Immutable
public data class TaskApprovalLabels(
    public val paymentCardTitle: String = "Approve payment",
    public val waiting: String = "Waiting for approval",
    public val card: String = "Card",
    public val app: String = "App",
    public val domain: String = "Domain",
    public val timeLeft: String = "{time} left",
    public val expired: String = "Approval expired",
    public val approve: String = "Approve",
    public val deny: String = "Deny",
    public val viewSwitch: String = "Approval view",
    public val details: String = "Details",
    public val device: String = "Device",
)

internal fun StreamLabels.statusLabel(status: StreamStatus, placeholderLabel: String?): String = when (status) {
    is StreamStatus.Reconnecting -> if (status.attempt > 1) {
        reconnectingAttempt.replace("{attempt}", status.attempt.toString()).replace("{max}", ReconnectPolicy.MAX_ATTEMPTS.toString())
    } else reconnecting
    else -> placeholderLabel ?: connecting
}

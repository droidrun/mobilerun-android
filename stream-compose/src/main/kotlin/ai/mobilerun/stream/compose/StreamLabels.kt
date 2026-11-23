// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.ReconnectPolicy
import ai.mobilerun.stream.core.StreamStatus
import androidx.compose.runtime.Immutable

/**
 * All stream chrome strings. Placeholders use {attempt} and {max}; caller labels are never translated.
 * [disconnected] shows for the first reconnect after an open session dropped; every other reconnect
 * uses [reconnecting] or [reconnectingAttempt].
 */
@Immutable
public class StreamLabels(
    public val connecting: String = "connecting",
    public val reconnecting: String = "reconnecting",
    public val reconnectingAttempt: String = "reconnecting ({attempt}/{max})",
    public val unavailable: String = "device stream unavailable",
    public val retry: String = "Retry",
    public val back: String = "Back",
    public val home: String = "Home",
    public val recent: String = "Recent",
    public val disconnected: String = "disconnected",
) {
    override fun equals(other: Any?): Boolean =
        other is StreamLabels &&
            connecting == other.connecting &&
            reconnecting == other.reconnecting &&
            reconnectingAttempt == other.reconnectingAttempt &&
            unavailable == other.unavailable &&
            retry == other.retry &&
            back == other.back &&
            home == other.home &&
            recent == other.recent &&
            disconnected == other.disconnected

    override fun hashCode(): Int {
        var result = connecting.hashCode()
        result = 31 * result + reconnecting.hashCode()
        result = 31 * result + reconnectingAttempt.hashCode()
        result = 31 * result + unavailable.hashCode()
        result = 31 * result + retry.hashCode()
        result = 31 * result + back.hashCode()
        result = 31 * result + home.hashCode()
        result = 31 * result + recent.hashCode()
        result = 31 * result + disconnected.hashCode()
        return result
    }

    override fun toString(): String =
        "StreamLabels(connecting=$connecting, reconnecting=$reconnecting, " +
        "reconnectingAttempt=$reconnectingAttempt, unavailable=$unavailable, retry=$retry, " +
        "back=$back, home=$home, recent=$recent, disconnected=$disconnected)"

    /** Creates a value with selected properties replaced. */
    public fun copy(
        connecting: String = this.connecting,
        reconnecting: String = this.reconnecting,
        reconnectingAttempt: String = this.reconnectingAttempt,
        unavailable: String = this.unavailable,
        retry: String = this.retry,
        back: String = this.back,
        home: String = this.home,
        recent: String = this.recent,
        disconnected: String = this.disconnected,
    ): StreamLabels = StreamLabels(
        connecting, reconnecting, reconnectingAttempt, unavailable, retry, back, home, recent, disconnected,
    )
}

/** Exactly the keys and English defaults of React's TaskApprovalLabels. */
@Immutable
public class TaskApprovalLabels(
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
) {
    override fun equals(other: Any?): Boolean =
        other is TaskApprovalLabels &&
            paymentCardTitle == other.paymentCardTitle &&
            waiting == other.waiting &&
            card == other.card &&
            app == other.app &&
            domain == other.domain &&
            timeLeft == other.timeLeft &&
            expired == other.expired &&
            approve == other.approve &&
            deny == other.deny &&
            viewSwitch == other.viewSwitch &&
            details == other.details &&
            device == other.device

    override fun hashCode(): Int {
        var result = paymentCardTitle.hashCode()
        result = 31 * result + waiting.hashCode()
        result = 31 * result + card.hashCode()
        result = 31 * result + app.hashCode()
        result = 31 * result + domain.hashCode()
        result = 31 * result + timeLeft.hashCode()
        result = 31 * result + expired.hashCode()
        result = 31 * result + approve.hashCode()
        result = 31 * result + deny.hashCode()
        result = 31 * result + viewSwitch.hashCode()
        result = 31 * result + details.hashCode()
        result = 31 * result + device.hashCode()
        return result
    }

    override fun toString(): String =
        "TaskApprovalLabels(paymentCardTitle=$paymentCardTitle, waiting=$waiting, card=$card, " +
        "app=$app, domain=$domain, timeLeft=$timeLeft, expired=$expired, approve=$approve, " +
        "deny=$deny, viewSwitch=$viewSwitch, details=$details, device=$device)"

    /** Creates a value with selected properties replaced. */
    public fun copy(
        paymentCardTitle: String = this.paymentCardTitle,
        waiting: String = this.waiting,
        card: String = this.card,
        app: String = this.app,
        domain: String = this.domain,
        timeLeft: String = this.timeLeft,
        expired: String = this.expired,
        approve: String = this.approve,
        deny: String = this.deny,
        viewSwitch: String = this.viewSwitch,
        details: String = this.details,
        device: String = this.device,
    ): TaskApprovalLabels = TaskApprovalLabels(
        paymentCardTitle,
        waiting,
        card,
        app,
        domain,
        timeLeft,
        expired,
        approve,
        deny,
        viewSwitch,
        details,
        device,
    )
}

internal fun StreamLabels.statusLabel(status: StreamStatus): String = when (status) {
    is StreamStatus.Reconnecting -> if (status.afterDrop && status.attempt <= 1) disconnected
    else if (status.attempt > 1) {
        reconnectingAttempt.replace("{attempt}", status.attempt.toString())
            .replace("{max}", ReconnectPolicy.MAX_ATTEMPTS.toString())
    } else reconnecting
    else -> connecting
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.math.ceil
import kotlin.time.Instant

/** Pending human approval returned by the backend. Unknown kinds are supported. */
public class PendingApproval(
    public val approvalId: String,
    public val kind: String,
    public val details: Map<String, Any?>,
    public val expiresAt: String,
    public val deviceId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingApproval &&
            approvalId == other.approvalId &&
            kind == other.kind &&
            details == other.details &&
            expiresAt == other.expiresAt &&
            deviceId == other.deviceId

    override fun hashCode(): Int {
        var result = approvalId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + details.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + deviceId.hashCode()
        return result
    }

    override fun toString(): String =
        "PendingApproval(approvalId=$approvalId, kind=$kind, details=$details, " +
        "expiresAt=$expiresAt, deviceId=$deviceId)"
}

/** A decision to forward to the backend; the backend alone authorizes it. */
public enum class ApprovalDecision(
    /** Lowercase value expected by the backend approval endpoint. */
    public val value: String,
) {
    /** Permit the requested action. */
    Approve("approve"),
    /** Reject the requested action. */
    Deny("deny"),
}

/** Validated display fields of a payment-card approval. */
public class PaymentCardDetails(
    public val card: String,
    public val targetPackage: String,
    public val targetHost: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PaymentCardDetails &&
            card == other.card &&
            targetPackage == other.targetPackage &&
            targetHost == other.targetHost

    override fun hashCode(): Int {
        var result = card.hashCode()
        result = 31 * result + targetPackage.hashCode()
        result = 31 * result + targetHost.hashCode()
        return result
    }

    override fun toString(): String =
        "PaymentCardDetails(card=$card, targetPackage=$targetPackage, targetHost=$targetHost)"
}

private const val PAYMENT_CARD_KIND = "payment_card"

/** Validated payment details for this approval, or null for unknown kinds/invalid details. */
public fun PendingApproval.paymentCardDetails(): PaymentCardDetails? =
    if (kind == PAYMENT_CARD_KIND) parsePaymentCardDetails(details) else null

/** Wrong-typed optional values count as absent; missing card/package makes details invalid. */
@InternalStreamApi
public fun parsePaymentCardDetails(details: Any?): PaymentCardDetails? {
    val fields = details as? Map<*, *> ?: return null
    fun text(key: String): String? = (fields[key] as? String)?.takeIf { it.isNotEmpty() }
    return PaymentCardDetails(
        card = text("label") ?: text("credentialName") ?: return null,
        targetPackage = text("targetPackage") ?: return null,
        targetHost = text("targetHost"),
    )
}

/** Milliseconds until an ISO-8601 deadline, negative once past, zero for an invalid date. Display only. */
@InternalStreamApi
public fun remainingMs(expiresAt: String, nowMs: Long): Long =
    try { Instant.parse(expiresAt).toEpochMilliseconds() - nowMs } catch (_: IllegalArgumentException) { 0L }

/** Rounded up seconds: "45s", "4m 58s", "1h 2m 5s". Negative values clamp to zero. */
@InternalStreamApi
public fun formatRemaining(ms: Long): String {
    val total = ceil(ms.coerceAtLeast(0).toDouble() / 1000).toLong()
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

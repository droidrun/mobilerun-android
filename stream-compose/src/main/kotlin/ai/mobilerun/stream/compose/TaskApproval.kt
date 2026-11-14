// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.ApprovalDecision
import ai.mobilerun.stream.core.PendingApproval
import ai.mobilerun.stream.core.formatRemaining
import ai.mobilerun.stream.core.parsePaymentCardDetails
import ai.mobilerun.stream.core.remainingMs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Inline approval card. Null renders nothing. Expiry is display only; actions remain available until
 * the backend replaces the approval. Optional [device] stays composed when the narrow layout switches
 * views. [actions] replaces the buttons and receives the same decision callback/progress.
 * Error text comes from the integrator and should already be localized for the user.
 */
@Composable
public fun TaskApproval(
    approval: PendingApproval?,
    onDecide: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
    isPending: Boolean = false,
    decision: ApprovalDecision? = null,
    error: String? = null,
    labels: TaskApprovalLabels = TaskApprovalLabels(),
    device: (@Composable () -> Unit)? = null,
    actions: (@Composable ((ApprovalDecision) -> Unit, Boolean, ApprovalDecision?) -> Unit)? = null,
) {
    if (approval == null) return
    val t = streamThemeTokens
    val card = if (approval.kind == "payment_card") parsePaymentCardDetails(approval.details) else null
    val title = if (card != null) labels.paymentCardTitle else labels.waiting
    var showDevice by remember(approval.approvalId) { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), approval.expiresAt) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    val remaining = remainingMs(approval.expiresAt, now)
    val shape = RoundedCornerShape(t.radius + 4.dp)
    BoxWithConstraints(modifier.background(t.card, shape).border(1.dp, t.border, shape).padding(16.dp)) {
        val wide = maxWidth >= 720.dp
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(title, Modifier.semantics { heading() }, style = t.typography.title.copy(color = t.cardForeground))
                BasicText(
                    if (remaining > 0) labels.timeLeft.replace("{time}", formatRemaining(remaining)) else labels.expired,
                    Modifier.background(t.muted, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    style = t.typography.caption.copy(color = t.mutedForeground),
                )
            }
            if (device != null && !wide) Row(
                Modifier.fillMaxWidth().semantics { contentDescription = labels.viewSwitch },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreamButton(labels.details, { showDevice = false }, Modifier.weight(1f), primary = !showDevice)
                StreamButton(labels.device, { showDevice = true }, Modifier.weight(1f), primary = showDevice)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().then(if (device != null && !wide && showDevice) Modifier.height(0.dp).clipToBounds() else Modifier),
                    ) {
                        if (card != null) Column(Modifier.border(1.dp, t.border, RoundedCornerShape(t.radius))) {
                            ApprovalFact(labels.card, card.card)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(t.border))
                            ApprovalFact(labels.app, card.targetPackage)
                            card.targetHost?.let { host ->
                                Box(Modifier.fillMaxWidth().height(1.dp).background(t.border))
                                ApprovalFact(labels.domain, host)
                            }
                        }
                    }
                    if (device != null && !wide) Box(
                        Modifier.fillMaxWidth().height(if (showDevice) 320.dp else 0.dp).clipToBounds(),
                    ) { device() }
                    if (error != null) BasicText(error,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = t.typography.body.copy(color = t.destructive))
                    if (actions != null) actions(onDecide, isPending, decision)
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamButton(labels.approve, { onDecide(ApprovalDecision.Approve) }, Modifier.weight(1f),
                            enabled = !isPending, primary = true, pending = isPending && decision == ApprovalDecision.Approve)
                        StreamButton(labels.deny, { onDecide(ApprovalDecision.Deny) }, Modifier.weight(1f),
                            enabled = !isPending, pending = isPending && decision == ApprovalDecision.Deny)
                    }
                }
                if (device != null && wide) Box(Modifier.width(256.dp).height(400.dp).clipToBounds()) { device() }
            }
        }
    }
}

@Composable
private fun ApprovalFact(label: String, value: String) {
    val t = streamThemeTokens
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = t.typography.caption.copy(color = t.mutedForeground))
        BasicText(value, style = t.typography.body.copy(color = t.cardForeground))
    }
}

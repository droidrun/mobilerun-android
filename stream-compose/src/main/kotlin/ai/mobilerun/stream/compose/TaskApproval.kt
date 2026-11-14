// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.ApprovalDecision
import ai.mobilerun.stream.core.PendingApproval
import ai.mobilerun.stream.core.PaymentCardDetails
import ai.mobilerun.stream.core.formatRemaining
import ai.mobilerun.stream.core.paymentCardDetails
import ai.mobilerun.stream.core.remainingMs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val WideBreakpoint = 768.dp
private val DeviceWidth = 256.dp
private val DeviceHeight = 400.dp
private val NarrowDeviceHeight = 320.dp
private val CardPadding = 16.dp
private val NoApprovalDevice: @Composable () -> Unit = {}

/** Immutable action-slot input. The callback only forwards a decision; the backend authorizes it. */
@Immutable
public class ApprovalActionsState internal constructor(
    public val isPending: Boolean,
    public val decision: ApprovalDecision?,
    public val labels: TaskApprovalLabels,
    public val onDecide: (ApprovalDecision) -> Unit,
)

/**
 * Null approval renders nothing. Expiry is display-only. The optional device slot is composed once
 * and moved between narrow/wide layouts without restarting it. Slots are non-null; the default
 * empty device omits its layout. Error text should be localized by the integrator.
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
    device: @Composable () -> Unit = NoApprovalDevice,
    actions: @Composable (ApprovalActionsState) -> Unit = { DefaultApprovalActions(it) },
) {
    if (approval == null) return
    val tokens = StreamTheme.tokens
    val card = approval.paymentCardDetails()
    val title = if (card != null) labels.paymentCardTitle else labels.waiting
    var showDevice by remember(approval.approvalId) { mutableStateOf(false) }
    val currentDevice by rememberUpdatedState(device)
    val movableDevice = remember { movableContentOf { currentDevice() } }
    val hasDevice = device !== NoApprovalDevice
    val actionState = remember(isPending, decision, labels, onDecide) {
        ApprovalActionsState(isPending, decision, labels, onDecide)
    }
    val shape = RoundedCornerShape(tokens.radius + 4.dp)
    BoxWithConstraints(modifier.background(tokens.card, shape).border(1.dp, tokens.border, shape)) {
        val split = maxWidth >= WideBreakpoint
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(title, Modifier.semantics { heading() },
                    style = tokens.typography.title.copy(color = tokens.cardForeground))
                ExpiryBadge(approval.expiresAt, labels)
            }
            if (hasDevice && !split) Row(
                Modifier.fillMaxWidth().semantics { contentDescription = labels.viewSwitch },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreamButton(labels.details, { showDevice = false }, Modifier.weight(1f),
                    primary = !showDevice)
                StreamButton(labels.device, { showDevice = true }, Modifier.weight(1f), primary = showDevice)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (hasDevice && !split) ApprovalViews(
                        showDevice,
                        details = { PaymentCardFacts(card, labels) },
                        device = {
                            Box(Modifier.fillMaxWidth().height(NarrowDeviceHeight)) { movableDevice() }
                        },
                    ) else PaymentCardFacts(card, labels)
                    if (error != null) BasicText(error,
                        Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        style = tokens.typography.body.copy(color = tokens.destructive))
                    actions(actionState)
                }
                if (hasDevice && split) Box(Modifier.width(DeviceWidth).height(DeviceHeight)) {
                    movableDevice()
                }
            }
        }
    }
}

/** Both views stay composed and measured; only the selected view participates in placement. */
@Composable
private fun ApprovalViews(
    showDevice: Boolean,
    details: @Composable () -> Unit,
    device: @Composable () -> Unit,
) {
    Layout(content = {
        Box { details() }
        Box { device() }
    }) { measurables, constraints ->
        val views = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val selected = views[if (showDevice) 1 else 0]
        layout(selected.width, selected.height) { selected.placeRelative(0, 0) }
    }
}

@Composable
private fun ExpiryBadge(expiresAt: String, labels: TaskApprovalLabels) {
    val tokens = StreamTheme.tokens
    val remaining by produceState(remainingMs(expiresAt, System.currentTimeMillis()), expiresAt) {
        value = remainingMs(expiresAt, System.currentTimeMillis())
        while (value > 0) {
            delay(minOf(1_000, value))
            value = remainingMs(expiresAt, System.currentTimeMillis())
        }
    }
    BasicText(
        if (remaining > 0) labels.timeLeft.replace("{time}", formatRemaining(remaining)) else labels.expired,
        Modifier.background(tokens.muted, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = tokens.typography.caption.copy(color = tokens.mutedForeground),
    )
}

@Composable
private fun PaymentCardFacts(card: PaymentCardDetails?, labels: TaskApprovalLabels) {
    if (card == null) return
    val tokens = StreamTheme.tokens
    Column(Modifier.border(1.dp, tokens.border, RoundedCornerShape(tokens.radius))) {
        ApprovalFact(labels.card, card.card)
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border))
        ApprovalFact(labels.app, card.targetPackage)
        card.targetHost?.let {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border))
            ApprovalFact(labels.domain, it)
        }
    }
}

@Composable
private fun ApprovalFact(label: String, value: String) {
    val tokens = StreamTheme.tokens
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = tokens.typography.caption.copy(color = tokens.mutedForeground))
        BasicText(value, style = tokens.typography.body.copy(color = tokens.cardForeground))
    }
}

@Composable
private fun DefaultApprovalActions(state: ApprovalActionsState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StreamButton(state.labels.approve, { state.onDecide(ApprovalDecision.Approve) }, Modifier.weight(1f),
            enabled = !state.isPending, primary = true,
            pending = state.isPending && state.decision == ApprovalDecision.Approve)
        StreamButton(state.labels.deny, { state.onDecide(ApprovalDecision.Deny) }, Modifier.weight(1f),
            enabled = !state.isPending, pending = state.isPending && state.decision == ApprovalDecision.Deny)
    }
}

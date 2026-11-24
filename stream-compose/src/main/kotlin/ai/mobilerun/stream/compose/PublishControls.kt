// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.core.DevicePublishCapabilities
import ai.mobilerun.stream.core.PublishException
import ai.mobilerun.stream.core.PublishKind
import ai.mobilerun.stream.core.PublishOptions
import ai.mobilerun.stream.core.PublishPhase
import ai.mobilerun.stream.core.PublishState
import ai.mobilerun.stream.core.StreamStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal fun showPublishPill(capabilities: DevicePublishCapabilities?, show: Boolean): Boolean =
    show && capabilities?.supportsMicrophone == true

@Composable
internal fun SessionPublishControls(session: DeviceStreamSession, status: StreamStatus, closed: Boolean,
    show: Boolean, labels: StreamLabels, modifier: Modifier,
    requestPermissions: suspend (PublishOptions) -> Unit,
) {
    val state by session.publishState.collectAsState()
    val scope = rememberCoroutineScope()
    var requesting by remember(session) { mutableStateOf(false) }
    if (!showPublishPill(session.publishCapabilities, show)) return
    PublishPill(state, status == StreamStatus.Connected && !closed && !requesting,
        session.publishCapabilities?.cameraInjection == true && session.cameraPublishSupported,
        labels, modifier, onToggle = toggle@ { video ->
            if (requesting) return@toggle
            requesting = true
            scope.launch {
                try {
                    val active = session.publishState.value.phase == PublishPhase.Active
                    val hadCamera = PublishKind.Video in session.publishState.value.kinds
                    if (active) session.stopPublish()
                    if (!active || video) {
                        val options = PublishOptions(audio = true, video = video && (!active || !hadCamera))
                        requestPermissions(options)
                        session.startPublish(options)
                    }
                } catch (_: PublishException) {
                    // The shared state/error flow contains the structured failure; render its label.
                } finally { requesting = false }
            }
        })
}

@Composable
internal fun PublishPill(state: PublishState, connected: Boolean, cameraSupported: Boolean,
    labels: StreamLabels, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    val t = StreamTheme.tokens
    val pending = state.phase in setOf(PublishPhase.Capturing, PublishPhase.Starting, PublishPhase.Accepted)
    val active = state.phase == PublishPhase.Active
    val camera = active && PublishKind.Video in state.kinds
    val label = when {
        pending -> labels.publishStarting
        state.phase != PublishPhase.Error -> ""
        state.reason == "permission_denied" -> labels.publishPermissionDenied
        state.reason == "publish_not_allowed" -> labels.publishNotAllowed
        state.reason == "publish_unsupported" -> labels.publishUnsupported
        else -> labels.publishFailed
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotEmpty()) BasicText(label, Modifier.padding(bottom = 6.dp),
            style = t.typography.caption.copy(color = t.destructive))
        Row(Modifier.clip(RoundedCornerShape(50)).background(t.card.copy(alpha = .94f))
            .border(1.dp, t.border, RoundedCornerShape(50)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PublishToggle(false, active, connected && !pending,
                if (active) labels.stopMicrophone else labels.microphone) { onToggle(false) }
            if (cameraSupported) PublishToggle(true, camera, connected && !pending,
                if (camera) labels.stopCamera else labels.camera) { onToggle(true) }
        }
    }
}

@Composable
private fun PublishToggle(camera: Boolean, live: Boolean, enabled: Boolean, label: String, click: () -> Unit) {
    val tokens = StreamTheme.tokens
    val color = (if (live) tokens.destructive else tokens.foreground).copy(alpha = if (enabled) 1f else .45f)
    Box(Modifier.size(48.dp).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = click), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(1.7.dp.toPx())
            if (camera) {
                drawRoundRect(color, Offset(0f, size.height * .2f), Size(size.width * .65f, size.height * .6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
                drawLine(color, Offset(size.width * .7f, size.height * .35f), Offset(size.width, size.height * .2f), stroke.width)
                drawLine(color, Offset(size.width, size.height * .2f), Offset(size.width, size.height * .8f), stroke.width)
                drawLine(color, Offset(size.width, size.height * .8f), Offset(size.width * .7f, size.height * .65f), stroke.width)
            } else {
                drawRoundRect(color, Offset(size.width * .35f, 0f), Size(size.width * .3f, size.height * .65f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = stroke)
                drawArc(color, 0f, 180f, false, Offset(size.width * .15f, size.height * .2f),
                    Size(size.width * .7f, size.height * .6f), style = stroke)
                drawLine(color, Offset(size.width * .5f, size.height * .8f), Offset(size.width * .5f, size.height), stroke.width)
                drawLine(color, Offset(size.width * .3f, size.height), Offset(size.width * .7f, size.height), stroke.width)
            }
        }
    }
}

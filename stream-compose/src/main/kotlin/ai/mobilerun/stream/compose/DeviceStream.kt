// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.android.DeviceStreamView
import ai.mobilerun.stream.android.StreamLifecycle
import ai.mobilerun.stream.android.SystemKey
import ai.mobilerun.stream.core.StreamStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** The navigation slot's immutable state and guarded action callback. */
@Immutable
public class StreamNavigationState internal constructor(
    public val canControl: Boolean,
    public val labels: StreamLabels,
    public val onAction: (SystemKey) -> Unit,
)

/** Immutable connection overlay state with a retry action. */
@Immutable
public class StreamOverlayState internal constructor(
    public val status: StreamStatus,
    public val labels: StreamLabels,
    public val retry: () -> Unit,
)

private val defaultPlaceholder: @Composable BoxScope.(StreamOverlayState) -> Unit = { state ->
    StreamStatusPill(state.labels.statusLabel(state.status), Modifier.align(Alignment.BottomStart).padding(16.dp))
}
private val defaultUnavailable: @Composable BoxScope.(StreamOverlayState) -> Unit = { state ->
    DefaultUnavailable(state.retry, state.labels)
}

/**
 * Video, connection overlay and optional navigation. The app owns session start/close.
 * Every slot has default content; pass an empty navigation lambda to omit the bar. Overlay slots
 * receive a BoxScope for alignment. One control state blocks unavailable/display-only input.
 */
@Composable
public fun DeviceStream(
    session: DeviceStreamSession,
    modifier: Modifier = Modifier,
    hasControl: Boolean = true,
    labels: StreamLabels = StreamLabels(),
    observeLifecycle: Boolean = true,
    showPublishControls: Boolean = true,
    requestPublishPermissions: suspend (ai.mobilerun.stream.core.PublishOptions) -> Unit = {},
    placeholder: @Composable BoxScope.(StreamOverlayState) -> Unit = defaultPlaceholder,
    unavailable: @Composable BoxScope.(StreamOverlayState) -> Unit = defaultUnavailable,
    navigationBar: @Composable (StreamNavigationState) -> Unit = {
        StreamNavigationBar(it.onAction, enabled = it.canControl, labels = it.labels)
    },
) {
    val status by session.status.collectAsState()
    val closed by session.closed.collectAsState()
    val canControl = hasControl && !closed && status != StreamStatus.Unavailable
    val context = LocalContext.current
    var view by remember { mutableStateOf<DeviceStreamView?>(null) }
    val navigation = remember(canControl, labels, view) {
        StreamNavigationState(canControl, labels) { if (canControl) view?.sendSystemKey(it) }
    }
    LaunchedEffect(session) {
        try { awaitCancellation() } finally {
            withContext(NonCancellable) { session.stopPublish() }
        }
    }
    DisposableEffect(session, context, observeLifecycle) {
        val lifecycle = if (observeLifecycle) StreamLifecycle(context, session) else null
        onDispose { lifecycle?.close() }
    }
    Column(modifier.background(StreamTheme.tokens.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { DeviceStreamView(it).also { created -> view = created } },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    it.detach()
                    if (view === it) view = null
                },
                update = {
                    it.hasControl = canControl
                    it.attach(session)
                },
            )
            StreamOverlay(status, labels, session::retry, placeholder, unavailable)
            SessionPublishControls(session, status, closed, showPublishControls, labels,
                Modifier.align(Alignment.BottomCenter).padding(16.dp), requestPublishPermissions)
        }
        navigationBar(navigation)
    }
}

@Composable
internal fun BoxScope.StreamOverlay(
    status: StreamStatus,
    labels: StreamLabels,
    retry: () -> Unit,
    placeholder: @Composable BoxScope.(StreamOverlayState) -> Unit = defaultPlaceholder,
    unavailable: @Composable BoxScope.(StreamOverlayState) -> Unit = defaultUnavailable,
) {
    val overlay = remember(status, labels, retry) { StreamOverlayState(status, labels, retry) }
    when (status) {
        StreamStatus.Connected -> Unit
        StreamStatus.Unavailable -> Box(
            Modifier.fillMaxSize().background(StreamTheme.tokens.background),
        ) { unavailable(overlay) }
        else -> placeholder(overlay)
    }
}

@Composable
private fun BoxScope.DefaultUnavailable(retry: () -> Unit, labels: StreamLabels) {
    val tokens = StreamTheme.tokens
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(labels.unavailable, style = tokens.typography.caption.copy(color = tokens.mutedForeground))
        StreamButton(labels.retry, retry)
    }
}

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

/** The navigation slot's immutable state and guarded action callback. */
@Immutable
public class StreamNavigationState internal constructor(
    public val canControl: Boolean,
    public val labels: StreamLabels,
    public val onAction: (SystemKey) -> Unit,
)

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
    placeholder: @Composable BoxScope.(StreamStatus, StreamLabels) -> Unit = { status, text ->
        StreamStatusPill(text.statusLabel(status), Modifier.align(Alignment.BottomStart).padding(16.dp))
    },
    unavailable: @Composable BoxScope.(retry: () -> Unit, StreamLabels) -> Unit = { retry, text ->
        DefaultUnavailable(retry, text)
    },
    navigationBar: @Composable (StreamNavigationState) -> Unit = {
        StreamNavigationBar(it.onAction, enabled = it.canControl, labels = it.labels)
    },
) {
    val status by session.status.collectAsState()
    val canControl = hasControl && status != StreamStatus.Unavailable
    val context = LocalContext.current
    var view by remember { mutableStateOf<DeviceStreamView?>(null) }
    val navigation = remember(canControl, labels, view) {
        StreamNavigationState(canControl, labels) { if (canControl) view?.sendSystemKey(it) }
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
        }
        navigationBar(navigation)
    }
}

@Composable
internal fun BoxScope.StreamOverlay(
    status: StreamStatus,
    labels: StreamLabels,
    retry: () -> Unit,
    placeholder: @Composable BoxScope.(StreamStatus, StreamLabels) -> Unit = { state, text ->
        StreamStatusPill(text.statusLabel(state), Modifier.align(Alignment.BottomStart).padding(16.dp))
    },
    unavailable: @Composable BoxScope.(() -> Unit, StreamLabels) -> Unit = { action, text ->
        DefaultUnavailable(action, text)
    },
) {
    when (status) {
        StreamStatus.Connected -> Unit
        StreamStatus.Unavailable -> Box(
            Modifier.fillMaxSize().background(StreamTheme.tokens.background),
        ) { unavailable(retry, labels) }
        else -> placeholder(status, labels)
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

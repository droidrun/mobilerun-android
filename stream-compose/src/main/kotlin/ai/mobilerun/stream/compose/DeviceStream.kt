// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.android.DeviceStreamView
import ai.mobilerun.stream.android.StreamLifecycle
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.android.SystemKey
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Video, connection overlay and optional navigation. The app owns [session] and calls start/close.
 * [hasControl] blocks all video and navigation input, while keeping the video visible. Every piece
 * of chrome can be replaced; pass null to [navigationBar] to omit it. The default lifecycle adapter
 * pauses background keep-alive and wakes recovery on foreground/network availability.
 */
@Composable
public fun DeviceStream(
    session: DeviceStreamSession,
    modifier: Modifier = Modifier,
    hasControl: Boolean = true,
    labels: StreamLabels = StreamLabels(),
    placeholderLabel: String? = null,
    observeLifecycle: Boolean = true,
    placeholder: (@Composable (StreamStatus, String) -> Unit)? = null,
    unavailable: (@Composable (retry: () -> Unit) -> Unit)? = null,
    navigationBar: (@Composable (onAction: (SystemKey) -> Unit, enabled: Boolean) -> Unit)? = { action, enabled ->
        NavigationBar(action, enabled = enabled, labels = labels)
    },
) {
    val status by session.status.collectAsState()
    val context = LocalContext.current
    var view by remember { mutableStateOf<DeviceStreamView?>(null) }
    DisposableEffect(session, context, observeLifecycle) {
        val lifecycle = if (observeLifecycle) StreamLifecycle(context, session) else null
        onDispose { lifecycle?.close() }
    }
    Column(modifier.background(streamThemeTokens.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { DeviceStreamView(it).also { created -> view = created } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.detach(); if (view === it) view = null },
                update = { it.hasControl = hasControl && status != StreamStatus.Unavailable; it.attach(session) },
            )
            StreamOverlay(status, labels, placeholderLabel, session::retry, placeholder, unavailable)
        }
        navigationBar?.invoke({ key -> if (hasControl) view?.sendSystemKey(key) }, hasControl)
    }
}

@Composable
internal fun BoxScope.StreamOverlay(
    status: StreamStatus,
    labels: StreamLabels,
    placeholderLabel: String?,
    retry: () -> Unit,
    placeholder: (@Composable (StreamStatus, String) -> Unit)?,
    unavailable: (@Composable (() -> Unit) -> Unit)?,
) {
    when (status) {
        StreamStatus.Connected -> Unit
        StreamStatus.Unavailable -> Box(Modifier.fillMaxSize().background(streamThemeTokens.background), Alignment.Center) {
            if (unavailable != null) unavailable(retry) else Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(labels.unavailable, style = streamThemeTokens.typography.caption.copy(color = streamThemeTokens.mutedForeground))
                StreamButton(labels.retry, retry)
            }
        }
        else -> {
            val label = labels.statusLabel(status, placeholderLabel)
            if (placeholder != null) placeholder(status, label)
            else StreamStatusPill(label, Modifier.align(Alignment.BottomStart).padding(16.dp))
        }
    }
}

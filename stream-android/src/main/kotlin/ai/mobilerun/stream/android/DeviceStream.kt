// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose host for [DeviceStreamView]. The caller owns [session]'s lifetime. With [hasControl]
 * false it renders without sending input. Use [DeviceStreamView] directly for keyboard visibility
 * or explicit system-key actions.
 */
@Composable
public fun DeviceStream(
    session: DeviceStreamSession,
    modifier: Modifier = Modifier,
    hasControl: Boolean = true,
) {
    AndroidView(
        factory = { context -> DeviceStreamView(context) },
        modifier = modifier,
        onRelease = { it.detach() },
        update = { view ->
            view.hasControl = hasControl
            view.attach(session)
        },
    )
}

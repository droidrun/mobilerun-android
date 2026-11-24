// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.PublishOptions
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** Session invokes this on main, only after server acceptance for an explicit pill tap. */
@Composable
internal fun rememberPublishPermissions(): suspend (PublishOptions) -> Unit {
    val context = LocalContext.current
    val registry = context.findPermissionRegistry()?.activityResultRegistry
    val pending = remember(registry) { mutableSetOf<CompletableDeferred<Unit>>() }
    DisposableEffect(registry) {
        onDispose { pending.toList().forEach { it.cancel() } }
    }
    return remember(context, registry) {
        { options: PublishOptions ->
            val permissions = publishPermissions(options).filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (permissions.isNotEmpty() && registry != null) {
                val result = CompletableDeferred<Unit>()
                pending += result
                // Each request has its own result route: a cancelled dialog cannot resume a later start.
                val launcher = registry.register("mobilerun-publish-${UUID.randomUUID()}",
                    ActivityResultContracts.RequestMultiplePermissions()) { result.complete(Unit) }
                try {
                    launcher.launch(permissions.toTypedArray())
                    result.await()
                } finally {
                    pending -= result
                    launcher.unregister()
                }
            }
            // Missing registry or refused grants are normalized by the SDK's actual grant check.
        }
    }
}

internal fun publishPermissions(options: PublishOptions): List<String> =
    if (options.video) listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    else listOf(Manifest.permission.RECORD_AUDIO)

internal fun Context.findPermissionRegistry(): ActivityResultRegistryOwner? {
    var current = this
    while (true) {
        if (current is ActivityResultRegistryOwner) return current
        val base = (current as? ContextWrapper)?.baseContext ?: return null
        if (base === current) return null
        current = base
    }
}

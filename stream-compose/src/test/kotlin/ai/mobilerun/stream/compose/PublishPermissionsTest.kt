// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.PublishOptions
import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublishPermissionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultRegistryLaunchesOnlyRequestedPermissionsAfterExplicitInvocation() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val requests = mutableListOf<List<String>>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                requests += (input as Array<String>).toList()
                dispatchResult(requestCode, android.app.Activity.RESULT_OK,
                    Intent().putExtra("androidx.activity.result.contract.extra.PERMISSIONS", input)
                        .putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS", IntArray(2)))
            }
        }
        val host = object : ContextWrapper(context), ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        lateinit var request: suspend (PublishOptions) -> Unit
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides host) { request = rememberPublishPermissions() }
            }
            compose.runOnIdle { assertTrue(requests.isEmpty()) }
            compose.runOnIdle { scope.launch { request(PublishOptions(true)) } }
            compose.waitForIdle()
            assertEquals(listOf(listOf(Manifest.permission.RECORD_AUDIO)), requests)
            compose.runOnIdle { scope.launch { request(PublishOptions(true, true)) } }
            compose.waitForIdle()
            assertEquals(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), requests.last())
        } finally { scope.cancel() }
    }
    @Test fun cancelledPermissionResultCannotCompleteTheNextRequest() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val requestCodes = mutableListOf<Int>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) { requestCodes += requestCode }
        }
        val host = object : ContextWrapper(context), ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        lateinit var request: suspend (PublishOptions) -> Unit
        lateinit var first: kotlinx.coroutines.Job
        lateinit var second: kotlinx.coroutines.Job
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides host) { request = rememberPublishPermissions() }
            }
            compose.runOnIdle { first = scope.launch { request(PublishOptions(true)) } }
            compose.waitForIdle()
            compose.runOnIdle { first.cancel() }
            compose.waitForIdle()
            compose.runOnIdle { second = scope.launch { request(PublishOptions(true, true)) } }
            compose.waitForIdle()
            assertEquals(2, requestCodes.size)
            compose.runOnIdle { registry.dispatchResult(requestCodes.first(), mapOf(Manifest.permission.RECORD_AUDIO to true)) }
            compose.waitForIdle()
            assertEquals(false, second.isCompleted)
            compose.runOnIdle { registry.dispatchResult(requestCodes.last(), mapOf(Manifest.permission.RECORD_AUDIO to true)) }
            compose.waitForIdle()
            assertTrue(second.isCompleted)
        } finally { scope.cancel() }
    }

}

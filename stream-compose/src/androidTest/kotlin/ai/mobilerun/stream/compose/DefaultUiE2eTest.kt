// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.android.StreamConfig
import ai.mobilerun.stream.core.*
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertNotNull

/** Test-only sample screens; runtime credentials are injected into app-private storage by the runner. */
class DefaultUiE2eTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<DeviceStreamSession>()

    @After fun cleanup() {
        sessions.forEach { it.close() }
        scope.cancel()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val bitmap = assertNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun liveVideoAndCredentialRecovery() = runBlocking {
        val json = JSONObject(File(context.filesDir, "stream-e2e.json").readText())
        val credentials = StreamCredentials(json.getString("streamUrl"), json.getString("streamToken"))
        val rejected = AtomicInteger()
        val expired = AtomicBoolean(false)
        val provider = { if (expired.get()) StreamCredentials(credentials.url, "expired") else credentials }
        var session by mutableStateOf(DeviceStreamSession(context, provider, scope,
            StreamConfig(logger = { line ->
                if (!expired.get() && (line.contains("http=401") || line.contains("http=404"))) rejected.set(1)
            })).also { sessions += it })
        var tokens by mutableStateOf(StreamThemeTokens.light())
        compose.setContent { StreamTheme(tokens) { DeviceStream(session, Modifier.fillMaxSize()) } }
        shot("live-connecting")
        session.start()
        withTimeout(60_000) {
            while (session.status.value != StreamStatus.Connected || session.videoSize.value == null) {
                check(rejected.get() == 0) { "PENDING: device HTTP 401/404" }
                delay(100)
            }
        }
        delay(2000)
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
        shot("live-connected-navigation")
        compose.runOnIdle { tokens = StreamThemeTokens.dark() }
        shot("live-dark")
        session.close()
        expired.set(true)
        val recovering = DeviceStreamSession(context, provider, scope).also { sessions += it }
        compose.runOnIdle { session = recovering }
        recovering.start()
        shot("live-expired-start")
        withTimeout(160_000) { recovering.status.first { it == StreamStatus.Unavailable } }
        shot("live-unavailable-observed")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("device stream unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        shot("live-unavailable-expired")
        compose.onNodeWithText("device stream unavailable").assertIsDisplayed()
        expired.set(false)
        compose.onNodeWithText("Retry").performClick()
        withTimeout(60_000) { recovering.status.first { it == StreamStatus.Connected } }
        withTimeout(20_000) { recovering.videoSize.first { it != null } }
        delay(2000)
        shot("live-retry-recovered")
    }
}

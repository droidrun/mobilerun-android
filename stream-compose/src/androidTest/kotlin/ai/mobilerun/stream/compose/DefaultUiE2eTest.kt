// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.android.StreamConfig
import ai.mobilerun.stream.core.*
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Test-only sample screens; runtime credentials are injected into app-private storage by the runner. */
class DefaultUiE2eTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<DeviceStreamSession>()

    @After fun cleanup() { sessions.forEach { it.close() }; scope.cancel() }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val bitmap = assertNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun chromeAndApprovalStates() {
        var status by mutableStateOf<StreamStatus>(StreamStatus.Connecting)
        var tokens by mutableStateOf(StreamThemeTokens.Light)
        var labels by mutableStateOf(StreamLabels())
        var approvals by mutableStateOf(false)
        var fetches = 0
        var pending: PendingApproval? = PendingApproval("example", "payment_card",
            mapOf("label" to "Personal card", "targetPackage" to "com.shop", "targetHost" to "shop.example"),
            java.time.Instant.now().plusSeconds(298).toString())
        val sent = mutableListOf<ApprovalDecision>()
        val controller = TaskApprovalController(scope, { fetches++; pending }, { _, _, decision ->
            delay(1000); sent += decision; pending = null
        }, subscribe = { _, _ -> {} })
        compose.setContent {
            MobilerunTheme(tokens) {
                val approval by controller.state.collectAsState()
                Column(Modifier.fillMaxSize().background(tokens.background).padding(16.dp)) {
                    if (approvals) TaskApproval(approval.approval, controller::decide, isPending = approval.isDeciding, decision = approval.decision,
                        device = { Box(Modifier.fillMaxSize().background(tokens.muted)) {
                            StreamOverlay(StreamStatus.Connecting, labels, null, {}, null, null)
                        } })
                    else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            StreamOverlay(status, labels, null, { status = StreamStatus.Connecting }, null, null)
                        }
                        NavigationBar({}, labels = labels)
                    }
                }
            }
        }
        compose.onNodeWithText("connecting").assertIsDisplayed(); shot("connecting")
        compose.runOnIdle { status = StreamStatus.Reconnecting(2) }
        compose.onNodeWithText("reconnecting (2/6)").assertIsDisplayed(); shot("reconnecting")
        compose.runOnIdle { status = StreamStatus.Unavailable }
        compose.onNodeWithText("device stream unavailable").assertIsDisplayed(); shot("unavailable")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("connecting").assertIsDisplayed(); shot("retry")
        compose.runOnIdle { tokens = StreamThemeTokens.Dark }; shot("dark")
        compose.runOnIdle {
            tokens = StreamThemeTokens.Light.copy(background = Color(0xFFEFF6FF), card = Color(0xFFDBEAFE), primary = Color(0xFF1D4ED8))
            labels = StreamLabels(connecting = "Preparing your phone", back = "Return", home = "Start", recent = "Apps")
        }
        compose.onNodeWithText("Preparing your phone").assertIsDisplayed(); shot("custom-theme-labels")
        compose.runOnIdle { tokens = StreamThemeTokens.Light; approvals = true }
        controller.setTask("example-task")
        compose.waitUntil(10_000) { controller.state.value.approval != null }
        compose.onNodeWithText("Approve payment").assertIsDisplayed(); shot("approval-countdown")
        if (compose.onAllNodesWithText("Device").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Device").performClick(); shot("approval-device")
            compose.onNodeWithText("Details").performClick()
        }
        compose.onNodeWithText("Approve").performClick()
        compose.onNodeWithText("Deny").assertIsNotEnabled(); shot("approval-approving")
        compose.waitUntil(10_000) { !controller.state.value.isDeciding && controller.state.value.approval == null }
        assertEquals(listOf(ApprovalDecision.Approve), sent); assertTrue(fetches >= 2)
        shot("approval-approved-refetched")
        pending = PendingApproval("second", "payment_card", mapOf("credentialName" to "Corporate", "targetPackage" to "com.shop"), "2026-01-01T00:00:00Z")
        controller.refetch()
        compose.waitUntil(10_000) { controller.state.value.approval != null }
        compose.onNodeWithText("Approval expired").assertIsDisplayed(); shot("approval-expired")
        compose.onNodeWithText("Deny").performClick()
        compose.waitUntil(10_000) { controller.state.value.approval == null && !controller.state.value.isDeciding }
        assertEquals(listOf(ApprovalDecision.Approve, ApprovalDecision.Deny), sent)
        shot("approval-denied-refetched")
        controller.close()
    }

    @Test fun liveVideoAndCredentialRecovery() = runBlocking {
        val json = JSONObject(File(context.filesDir, "stream-e2e.json").readText())
        val credentials = StreamCredentials(json.getString("streamUrl"), json.getString("streamToken"))
        val rejected = AtomicInteger()
        val expired = AtomicBoolean(false)
        val provider = DeviceStreamCredentials({ if (expired.get()) StreamCredentials(credentials.url, "expired") else credentials })
        var session by mutableStateOf(DeviceStreamSession(context, provider.provider, scope,
            StreamConfig(logger = { line ->
                if (!expired.get() && (line.contains("http=401") || line.contains("http=404"))) rejected.set(1)
            })).also { sessions += it })
        var tokens by mutableStateOf(StreamThemeTokens.Light)
        compose.setContent { MobilerunTheme(tokens) { DeviceStream(session, Modifier.fillMaxSize()) } }
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
        compose.runOnIdle { tokens = StreamThemeTokens.Dark }; shot("live-dark")
        session.close()
        expired.set(true)
        val recovering = DeviceStreamSession(context, provider.provider, scope).also { sessions += it }
        compose.runOnIdle { session = recovering }
        recovering.start()
        shot("live-expired-start")
        withTimeout(160_000) { recovering.status.first { it == StreamStatus.Unavailable } }
        shot("live-unavailable-observed")
        File(output, "recovery-state.txt").writeText("sameSession=${session === recovering} status=${session.status.value}\n" + compose.onRoot().printToString())
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("device stream unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        shot("live-unavailable-expired")
        compose.onNodeWithText("device stream unavailable").assertIsDisplayed()
        expired.set(false)
        compose.onNodeWithText("Retry").performClick()
        withTimeout(60_000) { recovering.status.first { it == StreamStatus.Connected } }
        withTimeout(20_000) { recovering.videoSize.first { it != null } }
        delay(2000); shot("live-retry-recovered")
    }
}

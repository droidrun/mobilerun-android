// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Offline gallery: no credentials, network or real approval task. */
class DefaultUiGalleryTest {
    // Cross-module fixtures keep the SDK-created state's constructor internal to core.
    private fun reconnecting(attempt: Int): StreamStatus.Reconnecting =
        StreamStatus.Reconnecting::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .newInstance(attempt)

    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun cleanup() {
        scope.cancel()
    }

    @Test fun gallery() {
        var status by mutableStateOf<StreamStatus>(StreamStatus.Connecting)
        var tokens by mutableStateOf(StreamThemeTokens.light())
        var labels by mutableStateOf(StreamLabels())
        val fetches = AtomicInteger()
        val pending = AtomicReference<PendingApproval?>(PendingApproval("example", "payment_card",
            mapOf("label" to "Personal card", "targetPackage" to "com.shop", "targetHost" to "shop.example"),
            java.time.Instant.now().plusSeconds(298).toString()))
        val sent = CopyOnWriteArrayList<ApprovalDecision>()
        val controller = TaskApprovalController(scope, { fetches.incrementAndGet()
        pending.get() },
            { _, _, decision ->
                delay(1500)
                sent += decision
                pending.set(null)
            }, subscribe = { _, _ -> AutoCloseable {} })
        val streamScene: @Composable ColumnScope.() -> Unit = {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                StreamOverlay(status, labels, { status = StreamStatus.Connecting })
            }
            StreamNavigationBar({}, enabled = status != StreamStatus.Unavailable, labels = labels)
        }
        val approvalScene: @Composable ColumnScope.() -> Unit = {
            val approval by controller.state.collectAsState()
            TaskApproval(approval.approval, controller::decide,
                isPending = approval.isDeciding, decision = approval.decision,
                device = { Box(Modifier.fillMaxSize().background(tokens.muted)) {
                    StreamOverlay(StreamStatus.Connecting, labels, {})
                } })
        }
        var scene by mutableStateOf(streamScene)
        compose.setContent {
            StreamTheme(tokens) {
                Column(Modifier.fillMaxSize().background(tokens.background).padding(16.dp)) { scene() }
            }
        }
        compose.onNodeWithText("connecting").assertIsDisplayed()
        shot("connecting")
        compose.runOnIdle { status = reconnecting(2) }
        compose.onNodeWithText("reconnecting (2/6)").assertIsDisplayed()
        shot("reconnecting")
        compose.runOnIdle { status = StreamStatus.Unavailable }
        compose.onNodeWithText("device stream unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home").assertIsNotEnabled()
        shot("unavailable")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("connecting").assertIsDisplayed()
        shot("retry")
        compose.runOnIdle { tokens = StreamThemeTokens.dark() }
        shot("dark")
        compose.runOnIdle {
            tokens = StreamThemeTokens.light().copy(
                background = Color(0xFFEFF6FF), card = Color(0xFFDBEAFE), primary = Color(0xFF1D4ED8))
            labels = StreamLabels(connecting = "Preparing your phone", back = "Return", home = "Start")
        }
        compose.onNodeWithText("Preparing your phone").assertIsDisplayed()
        shot("custom-theme-labels")
        compose.runOnIdle {
            tokens = StreamThemeTokens.light()
            scene = approvalScene
        }
        controller.setTask("example-task")
        compose.waitUntil(10_000) { controller.state.value.approval != null }
        compose.onNodeWithText("Approve payment").assertIsDisplayed()
        shot("approval-countdown")
        compose.onNodeWithText("Approve").performClick()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        shot("approval-approving")
        compose.waitUntil(10_000) { !controller.state.value.isDeciding && controller.state.value.approval == null }
        assertEquals(listOf(ApprovalDecision.Approve), sent.toList())
        assertTrue(fetches.get() >= 2)
        shot("approval-approved-refetched")
        pending.set(PendingApproval("second", "payment_card",
            mapOf("credentialName" to "Corporate", "targetPackage" to "com.shop"), "2026-01-01T00:00:00Z"))
        controller.refetch()
        compose.waitUntil(10_000) { controller.state.value.approval != null }
        compose.onNodeWithText("Approval expired").assertIsDisplayed()
        shot("approval-expired")
        compose.onNodeWithText("Deny").performClick()
        compose.waitUntil(10_000) { controller.state.value.approval == null && !controller.state.value.isDeciding }
        assertEquals(listOf(ApprovalDecision.Approve, ApprovalDecision.Deny), sent.toList())
        shot("approval-denied-refetched")
        controller.close()
    }

    @Test fun deviceView() {
        val approval = PendingApproval("device", "payment_card",
            mapOf("label" to "Personal card", "targetPackage" to "com.shop"), "invalid")
        compose.setContent {
            StreamTheme {
                TaskApproval(approval, {}, Modifier.width(375.dp).padding(16.dp), device = {
                    Box(Modifier.fillMaxSize().background(StreamTheme.tokens.muted)) {
                        StreamOverlay(StreamStatus.Connecting, StreamLabels(), {})
                    }
                })
            }
        }
        compose.onNodeWithText("Device").performClick()
        shot("approval-device")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.filesDir, "e2e-out").also { it.mkdirs() }
        val bitmap = assertNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

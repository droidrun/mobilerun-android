// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.SystemKey
import ai.mobilerun.stream.core.ApprovalDecision
import ai.mobilerun.stream.core.PendingApproval
import ai.mobilerun.stream.core.StreamStatus
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class DefaultUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun streamStatesAndRetry() {
        var status by mutableStateOf<StreamStatus>(StreamStatus.Connecting)
        var retries = 0
        compose.setContent { StreamTheme { Box {
            StreamOverlay(status, StreamLabels(connecting = "starting"), { retries++ })
        } } }
        compose.onNodeWithText("starting").assertIsDisplayed()
        compose.runOnIdle { status = StreamStatus.Reconnecting(2) }
        compose.onNodeWithText("reconnecting (2/6)").assertIsDisplayed()
        compose.runOnIdle { status = StreamStatus.Connected }
        compose.onNodeWithText("reconnecting (2/6)").assertDoesNotExist()
        compose.runOnIdle { status = StreamStatus.Unavailable }
        compose.onNodeWithText("device stream unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun customSlotsAndLabels() {
        var status by mutableStateOf<StreamStatus>(StreamStatus.Connecting)
        var retries = 0
        compose.setContent { Box {
            StreamOverlay(status, StreamLabels(connecting = "Preparing"), { retries++ },
                { state -> BasicText("Custom ${state.labels.connecting}") },
                { state -> StreamButton("Try again", state.retry) })
        } }
        compose.onNodeWithText("Custom Preparing").assertIsDisplayed()
        compose.runOnIdle { status = StreamStatus.Unavailable }
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun navigationMapsKeysAndBlocksDisplayOnly() {
        var enabled by mutableStateOf(true)
        val sent = mutableListOf<SystemKey>()
        compose.setContent { StreamNavigationBar({ sent += it }, enabled = enabled) }
        listOf("Back", "Home", "Recent").forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.runOnIdle {
            assertEquals(listOf(SystemKey.Back, SystemKey.Home, SystemKey.Recent), sent)
            enabled = false
        }
        compose.onNodeWithContentDescription("Home").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(3, sent.size) }
    }
    @Test fun expiredAndUnknownApprovalsStillForwardDecisionsAndPendingDisables() {
        var pending by mutableStateOf(false)
        var current by mutableStateOf<PendingApproval?>(PendingApproval("one", "future_kind", emptyMap(), "invalid"))
        val sent = mutableListOf<ApprovalDecision>()
        compose.setContent { TaskApproval(current, { sent += it }, isPending = pending) }
        compose.onNodeWithText("Waiting for approval").assertIsDisplayed()
        compose.onNodeWithText("Approval expired").assertIsDisplayed()
        compose.onNodeWithText("Approve").performClick()
        compose.onNodeWithText("Deny").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ApprovalDecision.Approve, ApprovalDecision.Deny), sent)
            pending = true
        }
        compose.onNodeWithText("Approve").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        compose.runOnIdle { current = null }
        compose.onNodeWithText("Waiting for approval").assertDoesNotExist()
    }
    @Test fun paymentFieldsCustomActionsAndDeviceStayMounted() {
        var mounts = 0
        val card = PendingApproval("one", "payment_card", mapOf("label" to "Personal", "targetPackage" to "com.shop", "targetHost" to "shop.test"), "invalid")
        compose.setContent { TaskApproval(card, {}, labels = TaskApprovalLabels(paymentCardTitle = "Confirm"),
            device = {
                DisposableEffect(Unit) {
                    mounts++
                    onDispose { mounts-- }
                }
                BasicText("Video slot")
            },
            actions = { _ -> BasicText("Own actions") }) }
        compose.onNodeWithText("Confirm").assertIsDisplayed()
        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithText("shop.test").assertIsDisplayed()
        compose.onNodeWithText("Own actions").assertIsDisplayed()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Video slot").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.runOnIdle { assertEquals(1, mounts) }
    }
    @Test
    @Config(qualifiers = "w400dp-h900dp-night")
    fun darkSystemDoesNotChangeDefaultThemeUnlessExplicitlyRequested() {
        var default = Color.Transparent
        var system = Color.Transparent
        var explicit = Color.Transparent
        compose.setContent {
            default = StreamTheme.tokens.background
            StreamTheme(followSystem = true) { system = StreamTheme.tokens.background }
            StreamTheme(StreamThemeTokens.dark()) { explicit = StreamTheme.tokens.background }
        }
        compose.runOnIdle {
            assertEquals(StreamThemeTokens.light().background, default)
            assertEquals(StreamThemeTokens.dark().background, system)
            assertEquals(StreamThemeTokens.dark().background, explicit)
        }
    }

    @Test fun deviceCompositionSurvivesSwitchesAcrossBothLayouts() {
        var width by mutableStateOf(375.dp)
        var mounted = 0
        var disposed = 0
        val card = PendingApproval("one", "payment_card",
            mapOf("label" to "Personal", "targetPackage" to "com.shop"), "invalid")
        compose.setContent {
            TaskApproval(card, {}, Modifier.requiredWidth(width), device = {
                DisposableEffect(Unit) {
                    mounted++
                    onDispose { disposed++ }
                }
                BasicText("Retained video")
            })
        }
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Retained video").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Retained video").assertIsNotDisplayed()
        compose.runOnIdle { width = 1000.dp }
        compose.waitForIdle()
        compose.runOnIdle { width = 375.dp }
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Retained video").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounted)
            assertEquals(0, disposed)
        }
    }

    @Test fun approvalErrorsHaveAssertiveLiveRegions() {
        val approval = PendingApproval("one", "future_kind", emptyMap(), "invalid")
        compose.setContent { TaskApproval(approval, {}, error = "Decision failed") }
        compose.onNodeWithText("Decision failed").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

}

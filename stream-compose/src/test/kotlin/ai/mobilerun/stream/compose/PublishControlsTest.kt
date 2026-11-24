// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class PublishControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibilityRequiresDirectMicrophoneAndRespectsOptOut() {
        assertFalse(showPublishPill(null, true))
        assertFalse(showPublishPill(DevicePublishCapabilities(false, true, "direct"), true))
        assertFalse(showPublishPill(DevicePublishCapabilities(true, true, "relay"), true))
        assertFalse(showPublishPill(DevicePublishCapabilities(true, true, "direct"), false))
        assertTrue(showPublishPill(DevicePublishCapabilities(true, false, "direct"), true))
    }

    @Test fun controlsUseLabelsAndDisableDuringStartThenExposeStopActions() {
        var state by mutableStateOf(PublishState())
        val clicks = mutableListOf<Boolean>()
        val labels = StreamLabels(microphone = "Mic", camera = "Cam", publishStarting = "Preparing",
            stopMicrophone = "End mic", stopCamera = "End cam")
        compose.setContent { Box(Modifier.padding(top = 40.dp)) { PublishPill(state, true, true, labels, onToggle = { clicks += it }) } }
        compose.onNodeWithContentDescription("Mic").performClick()
        compose.onNodeWithContentDescription("Cam").performClick()
        compose.runOnIdle {
            assertEquals(listOf(false, true), clicks)
            state = PublishState(PublishPhase.Starting)
        }
        compose.onNodeWithText("Preparing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mic").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cam").assertIsNotEnabled()
        compose.runOnIdle { state = PublishState(PublishPhase.Active, kinds = listOf(PublishKind.Audio, PublishKind.Video)) }
        compose.onNodeWithContentDescription("End mic").assertIsEnabled()
        compose.onNodeWithContentDescription("End cam").assertIsEnabled()
    }

    @Test fun permissionAndServerRefusalHaveDistinctOverridableLabels() {
        var state by mutableStateOf(PublishState(PublishPhase.Error, reason = "permission_denied"))
        val labels = StreamLabels(publishPermissionDenied = "Grant access", publishNotAllowed = "Access refused")
        compose.setContent { Box(Modifier.padding(top = 40.dp)) { PublishPill(state, true, false, labels, onToggle = {}) } }
        compose.onNodeWithText("Grant access").assertIsDisplayed()
        compose.onNodeWithContentDescription("Camera").assertDoesNotExist()
        compose.runOnIdle { state = PublishState(PublishPhase.Error, reason = "publish_not_allowed") }
        compose.onNodeWithText("Access refused").assertIsDisplayed()
        assertEquals("Grant access", labels.copy(camera = "Video").publishPermissionDenied)
        assertNotEquals(labels, labels.copy(publishNotAllowed = "Other"))
    }
    @Test fun commonPublishLabelKeysAndDefaults() {
        val labels = StreamLabels()
        assertEquals(listOf("Microphone", "Camera", "Stop microphone", "Stop camera", "Starting inputs",
            "Microphone or camera permission denied", "Could not publish inputs", "Publishing is not allowed"),
            listOf(labels.microphone, labels.camera, labels.stopMicrophone, labels.stopCamera,
                labels.publishStarting, labels.publishPermissionDenied, labels.publishFailed, labels.publishNotAllowed))
    }

    @Test fun backgroundTapsReachStreamAndStatusDoesNotMoveButtons() {
        var state by mutableStateOf(PublishState())
        var streamTaps = 0
        var buttonTaps = 0
        compose.setContent {
            Box(Modifier.size(300.dp).testTag("root").observePublishProximity {}) {
                Box(Modifier.fillMaxSize().clickable { streamTaps++ })
                PublishPill(state, true, true, StreamLabels(),
                    Modifier.align(Alignment.BottomCenter).testTag("pill"), onToggle = { buttonTaps++ })
            }
        }
        val micBefore = compose.onNodeWithContentDescription("Microphone").getUnclippedBoundsInRoot()
        // The six-dp decorative padding is outside either 48-dp button.
        compose.onNodeWithTag("pill").performTouchInput { click(Offset(2f, height / 2f)) }
        compose.runOnIdle {
            assertEquals(1, streamTaps)
            assertEquals(0, buttonTaps)
            state = PublishState(PublishPhase.Error, reason = "permission_denied")
        }
        assertEquals(micBefore, compose.onNodeWithContentDescription("Microphone").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Microphone").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, buttonTaps) }
        val text = compose.onNodeWithText("Microphone or camera permission denied").getUnclippedBoundsInRoot()
        assertTrue(text.bottom <= micBefore.top)
    }



}

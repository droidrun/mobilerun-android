// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.core.PublishKind
import ai.mobilerun.stream.core.PublishPhase
import ai.mobilerun.stream.core.PublishState
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Offline click-through and screenshots; no stream or media is opened. */
class PublishGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pillClickThrough() {
        var visible by mutableStateOf(false)
        var state by mutableStateOf(PublishState())
        val clicks = mutableListOf<Boolean>()
        compose.setContent {
            StreamTheme {
                Box(Modifier.fillMaxSize().background(StreamTheme.tokens.muted)) {
                    if (visible) PublishPill(state, true, true, StreamLabels(),
                        Modifier.align(Alignment.BottomCenter).padding(16.dp), onToggle = { clicks += it })
                }
            }
        }
        shot("publish-before")
        compose.runOnIdle { visible = true }
        compose.onNodeWithContentDescription("Microphone").performClick()
        compose.onNodeWithContentDescription("Camera").performClick()
        compose.runOnIdle {
            assertEquals(listOf(false, true), clicks)
            state = PublishState(PublishPhase.Active, kinds = listOf(PublishKind.Audio, PublishKind.Video))
        }
        compose.onNodeWithContentDescription("Stop microphone").assertIsEnabled()
        compose.onNodeWithContentDescription("Stop camera").assertIsEnabled()
        val microphoneBounds = compose.onNodeWithContentDescription("Stop microphone").getUnclippedBoundsInRoot()
        shot("publish-after")
        compose.runOnIdle { state = PublishState(PublishPhase.Starting) }
        compose.onNodeWithContentDescription("Microphone").assertIsNotEnabled()
        compose.onNodeWithText("Starting inputs").assertIsDisplayed()
        shot("publish-starting")
        compose.runOnIdle { state = PublishState(PublishPhase.Error, reason = "permission_denied") }
        compose.onNodeWithText("Microphone or camera permission denied").assertIsDisplayed()
        assertEquals(microphoneBounds, compose.onNodeWithContentDescription("Microphone").getUnclippedBoundsInRoot())
        shot("publish-permission")
    }

    @Test fun pillFadesAfterThreeSecondsAndNearbyPointerRestoresIt() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var interaction by remember { mutableLongStateOf(0) }
            Box(Modifier.size(300.dp).background(Color.Black).testTag("root")
                .observePublishProximity { interaction++ }) {
                PublishPill(PublishState(), true, true, StreamLabels(),
                    Modifier.align(Alignment.BottomCenter).testTag("pill"), interaction, {})
            }
        }
        compose.mainClock.advanceTimeBy(32)
        val initial = compose.onNodeWithTag("pill").captureToImage().toPixelMap()[10, 10].red
        compose.mainClock.advanceTimeBy(2_950)
        assertEquals(initial, compose.onNodeWithTag("pill").captureToImage().toPixelMap()[10, 10].red)
        compose.mainClock.advanceTimeBy(100)
        val faded = compose.onNodeWithTag("pill").captureToImage().toPixelMap()[10, 10].red
        assertTrue(kotlin.math.abs(faded - initial * .5f) < .02f)
        compose.onNodeWithTag("root").performTouchInput { down(Offset(width / 2f, height - 70f)); up() }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(initial, compose.onNodeWithTag("pill").captureToImage().toPixelMap()[10, 10].red)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.filesDir, "publish-gallery").also { it.mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

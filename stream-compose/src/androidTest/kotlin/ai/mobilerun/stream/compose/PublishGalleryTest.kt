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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
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
        shot("publish-after")
        compose.runOnIdle { state = PublishState(PublishPhase.Starting) }
        compose.onNodeWithContentDescription("Microphone").assertIsNotEnabled()
        compose.onNodeWithText("Starting capture").assertIsDisplayed()
        shot("publish-starting")
        compose.runOnIdle { state = PublishState(PublishPhase.Error, reason = "permission_denied") }
        compose.onNodeWithText("Microphone or camera permission required").assertIsDisplayed()
        shot("publish-permission")
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

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.compose.StreamTheme
import ai.mobilerun.stream.compose.StreamNavigationBar
import androidx.compose.ui.graphics.Color
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
class SampleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeButtonsApplyTokensAndCustomLabels() {
        var background = Color.Transparent
        var primary = Color.Transparent
        compose.setContent {
            SampleTheme { labels ->
                background = StreamTheme.tokens.background
                primary = StreamTheme.tokens.primary
                StreamNavigationBar({}, labels = labels)
            }
        }
        compose.onNodeWithText("Dark").performClick()
        compose.runOnIdle { assertEquals(Color(0xFF0A0A0A), background) }
        compose.onNodeWithText("Brand").performClick()
        compose.runOnIdle {
            assertEquals(Color.White, background)
            assertEquals(Color(0xFF2563EB), primary)
        }
        compose.onNodeWithContentDescription("Start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Apps").assertIsDisplayed()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
    }

    @Test fun localDemoCountsDownAndRefetchesAfterApproveAndDeny() {
        compose.setContent { StreamTheme { DemoApproval() } }
        compose.onNodeWithText("Demo approval").performScrollTo().performClick()
        compose.onNode(SemanticsMatcher("countdown is visible") { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .any { it.text.matches(Regex("[0-9]+s left")) }
        }).assertIsDisplayed()
        compose.onNodeWithText("Approve").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Demo: approve · refetched (3)").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Demo: approve · refetched (3)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Approve").assertDoesNotExist()
        compose.onNodeWithText("Demo approval").performScrollTo().performClick()
        compose.onNodeWithText("Deny").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Demo: deny · refetched (5)").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Demo: deny · refetched (5)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Deny").assertDoesNotExist()
    }
}

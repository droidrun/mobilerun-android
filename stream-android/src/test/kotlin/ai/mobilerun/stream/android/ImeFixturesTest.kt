// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.conformance.runFixtureCases
import android.app.Application
import android.view.View
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Replays IME vectors through the native composition buffer and the shared pure commit path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImeFixturesTest {
    // The shared vectors encode INJECT_TEXT. Android's IME uses SET_CLIPBOARD/paste for
    // Unicode that the remote key-character injector cannot type. Pin those wire bytes here;
    // keep exercising every native composition/control vector and the unchanged shared fixtures.
    private val unicodePaste = mapOf(
        "unicode" to listOf("090000000000000000010000000cc3a9e4b8adf09f988065cc81"),
        "unicode line separators printable" to listOf("090000000000000000010000000961e280a862e280a963"),
        "lone surrogate replacement" to listOf(
            "090000000000000000010000000461efbfbd", "00003d0000000000000000000000",
            "00013d0000000000000000000000", "010100000062"),
        "composition finalized" to listOf("090000000000000000010000000de697a5e69cace8aa9ef09f9880"),
    )
    @Test fun allImeVectors() {
        val file = "input/ime.json"
        val cases = Json.parseToJsonElement(File(requireNotNull(System.getProperty("conformance.fixtures")), file)
            .readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(21, cases.size, "pinned v0.2.0 IME case count")
        runFixtureCases(file, cases) { name, case ->
            val messages = mutableListOf<Pair<String, String>>()
            val input = StreamInputConnection(View(RuntimeEnvironment.getApplication()), { true },
                { bytes -> messages += "control" to bytes.joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                } }, { error("fixture unexpectedly used hardware keys") })
            fun text(key: String) = case.getValue(key).jsonPrimitive.content
            when (text("operation")) {
                "commit" -> input.commitText(text("text"), 1)
                "composition_update" -> input.setComposingText(text("text"), 1)
                "composition_end" -> {
                    input.setComposingText(text("text"), 1)
                    assertTrue(messages.isEmpty(), "composition must remain local")
                    input.finishComposingText()
                }
                "delete" -> {
                    input.setComposingText(text("composition_text"), 1)
                    input.deleteSurroundingText(1, 0)
                }
                else -> error("unknown IME operation")
            }
            val expected = case.getValue("expect").jsonArray.map {
                it.jsonObject.getValue("channel").jsonPrimitive.content to
                    it.jsonObject.getValue("hex").jsonPrimitive.content
            }
            assertEquals(unicodePaste[name]?.map { "control" to it } ?: expected, messages, name)
            input.closeConnection()
        }
    }
}

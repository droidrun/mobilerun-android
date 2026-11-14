// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

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
    @Test fun allImeVectors() {
        val file = "input/ime.json"
        val cases = Json.parseToJsonElement(File(requireNotNull(System.getProperty("conformance.fixtures")), file)
            .readText()).jsonObject.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        var exercised = 0
        for (element in cases) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            exercised++
            try {
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
                assertEquals(expected, messages, name)
                input.closeConnection()
            } catch (failure: AssertionError) {
                failures += "$name: ${failure.message}"
            }
        }
        println("conformance: $file exercised $exercised of ${cases.size}")
        assertEquals(cases.size, exercised)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}

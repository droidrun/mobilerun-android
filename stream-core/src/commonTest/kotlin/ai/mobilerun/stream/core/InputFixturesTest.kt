// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.conformance.fixtureObject
import ai.mobilerun.stream.conformance.runFixtureCases
import ai.mobilerun.stream.conformance.toHex
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Every vector is replayed through the same pure routing used by the Android view and IME. */
class InputFixturesTest {
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean

    private fun messages(file: String, replay: (JsonObject, (ByteArray) -> Unit, (ByteArray) -> Unit) -> Unit) {
        runFixtureCases("input/$file.json", fixtureObject("input/$file.json").getValue("cases").jsonArray) { name, case ->
            val actual = mutableListOf<Pair<String, String>>()
            replay(case, { actual += "control" to it.toHex() }, { actual += "hover" to it.toHex() })
            val expected = case.getValue("expect").jsonArray.map {
                it.jsonObject.text("channel") to it.jsonObject.text("hex")
            }
            assertEquals(expected, actual, name)
        }
    }

    @Test fun keys() = messages("keys") { case, send, _ ->
        val input = KeyInput()
        for (event in case.getValue("events").jsonArray.map { it.jsonObject }) {
            val meta = (if (event.flag("shift")) 1 else 0) or (if (event.flag("alt")) 2 else 0) or
                (if (event.flag("ctrl")) 4096 else 0) or (if (event.flag("meta")) 65536 else 0)
            input.route(event.text("code"), event.text("key"), event.text("type") == "keydown", meta,
                { event.text("clipboard") }, event.flag("caps_lock"), send)
        }
    }

    @Test fun pointers() = messages("pointers") { case, send, hover ->
        val input = PointerInput(send)
        val width = case.int("video_width")
        val height = case.int("video_height")
        val geometry = InputGeometry.fit(width, height, VideoSize(width, height, 0))
        for (event in case.getValue("events").jsonArray.map { it.jsonObject }) {
            if (geometry == null) continue
            val mouse = event.flag("mouse")
            val id = if (mouse) PointerInput.MOUSE_ID else event.int("id")
            when (event.text("type")) {
                "down" -> input.down(id, event.number("x"), event.number("y"), geometry, event.int("button"))
                "move" -> {
                    if (mouse && !input.isActive(id)) {
                        geometry.hover(event.number("x"), event.number("y"), event.int("buttons"))?.let(hover)
                    } else input.move(id, event.number("x"), event.number("y"))
                    // Vectors specify routing; they do not simulate frame coalescing.
                    input.flushMoves()
                }
                "up" -> input.up(id, event.int("button"))
                "cancel" -> input.cancel(id)
                else -> error("unknown pointer operation")
            }
        }
    }

    @Test fun geometry() {
        val file = "input/geometry.json"
        runFixtureCases(file, fixtureObject(file).getValue("cases").jsonArray) { name, case ->
            val geometry = InputGeometry.fit(case.int("view_width"), case.int("view_height"),
                VideoSize(case.int("video_width"), case.int("video_height"), case.int("rotation")))
            val point = geometry?.map(case.number("x"), case.number("y"))
            val expected = case.getValue("expect")
            if (expected == JsonNull) assertNull(point, name) else {
                assertNotNull(geometry, name)
                assertNotNull(point, name)
                val value = expected.jsonObject
                assertEquals(value.int("width"), geometry.videoWidth, name)
                assertEquals(value.int("height"), geometry.videoHeight, name)
                assertEquals(value.number("x"), point.x, 1e-6, name)
                assertEquals(value.number("y"), point.y, 1e-6, name)
            }
        }
    }

    @Test fun scroll() = messages("scroll") { case, send, _ ->
        val width = case.int("video_width")
        val height = case.int("video_height")
        InputGeometry.fit(width, height, VideoSize(width, height, 0))?.scroll(case.number("x"), case.number("y"),
            case.number("h"), case.number("v"), case.int("buttons"))?.let(send)
    }
}

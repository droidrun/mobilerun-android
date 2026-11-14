// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import ai.mobilerun.stream.conformance.fixtureObject
import ai.mobilerun.stream.conformance.runFixtureCases
import ai.mobilerun.stream.conformance.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every case of `control/<builder>.json` must encode to exactly its `hex`. */
class ControlFixturesTest {
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
    private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.double
    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.bool(name: String) = getValue(name).jsonPrimitive.boolean

    private fun check(builder: String, encode: (JsonObject) -> ByteArray) {
        val file = "control/$builder.json"
        runFixtureCases(file, fixtureObject(file).getValue("cases").jsonArray) { _, case ->
            val expected = case.string("hex")
            val actual = encode(case.getValue("args").jsonObject).toHex()
            assertEquals(expected, actual)
        }
    }

    @Test
    fun touch() = check("touch") { a ->
        ControlMessages.touch(
            action = a.int("action"),
            pointerId = a.long("pointerId"),
            videoWidth = a.int("videoWidth"),
            videoHeight = a.int("videoHeight"),
            x = a.double("x"),
            y = a.double("y"),
            pressure = a.double("pressure"),
            actionButton = a.int("actionButton"),
            buttons = a.int("buttons"),
        )
    }

    @Test
    fun scroll() = check("scroll") { a ->
        ControlMessages.scroll(
            videoWidth = a.int("videoWidth"),
            videoHeight = a.int("videoHeight"),
            x = a.double("x"),
            y = a.double("y"),
            hscrollTicks = a.double("hscrollTicks"),
            vscrollTicks = a.double("vscrollTicks"),
            buttons = a.int("buttons"),
        )
    }

    @Test
    fun hover() = check("hover") { a ->
        ControlMessages.hover(
            videoWidth = a.int("videoWidth"),
            videoHeight = a.int("videoHeight"),
            x = a.double("x"),
            y = a.double("y"),
            buttons = a.int("buttons"),
        )
    }

    @Test
    fun keycode() = check("keycode") { a ->
        ControlMessages.keycode(
            action = a.int("action"),
            keycode = a.int("keycode"),
            repeat = a.int("repeat"),
            metaState = a.int("metaState"),
        )
    }

    @Test
    fun text() = check("text") { a -> ControlMessages.text(text = a.string("text")) }

    @Test
    fun setClipboard() = check("set-clipboard") { a ->
        ControlMessages.setClipboard(text = a.string("text"), paste = a.bool("paste"))
    }
}

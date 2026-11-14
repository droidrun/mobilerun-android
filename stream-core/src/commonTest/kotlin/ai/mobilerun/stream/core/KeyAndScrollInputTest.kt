// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AndroidKeys
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.KeyboardCodes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KeyAndScrollInputTest {
    @Test
    fun wheelModesMatchReactAndAndroidTicksAreNotScaledTwice() {
        val cases = listOf(
            Triple(ScrollInput.Unit.PIXEL, 100.0, ScrollInput.Ticks(-1.0, -1.0)),
            Triple(ScrollInput.Unit.LINE, 1.0, ScrollInput.Ticks(-1.0, -1.0)),
            Triple(ScrollInput.Unit.PAGE, 1.0, ScrollInput.Ticks(-3.0, -3.0)),
            Triple(ScrollInput.Unit.ANDROID_AXIS, 1.0, ScrollInput.Ticks(-1.0, 1.0)),
        )
        cases.forEach { (unit, delta, expected) ->
            assertEquals(expected, ScrollInput.ticks(delta, delta, unit), unit.name)
        }
        val ticks = ScrollInput.ticks(0.0, 1.0, ScrollInput.Unit.ANDROID_AXIS)
        val message = ControlMessages.scroll(100, 100, 50.0, 50.0, ticks.horizontal, ticks.vertical)
        // One Android tick is 1/16 in the wire's signed fixed point: 0x0800, little-endian.
        assertEquals(0, message[15].toInt())
        assertEquals(8, message[16].toInt())
    }

    @Test
    fun printableCharactersOnlyInjectTextOnKeyDown() {
        for ((code, text) in listOf("KeyA" to "a", "KeyA" to "A", "KeyE" to "é", "Space" to " ")) {
            val messages = route(code, text, 0)
            assertEquals(1, messages.size, text)
            assertContentEquals(ControlMessages.text(text), messages.single())
        }
    }

    @Test
    fun controlKeysInjectDownAndUpWithPlatformMetaState() {
        val codes = listOf("Backspace", "Delete", "Enter", "Tab", "Escape", "Home", "End",
            "PageUp", "PageDown", "Insert", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
            "F1", "F12", "ShiftLeft", "ControlRight", "AltLeft", "MetaRight")
        for (code in codes) {
            val messages = route(code, null, AndroidKeys.META_SHIFT_ON)
            assertEquals(2, messages.size, code)
            for (action in 0..1) assertContentEquals(ControlMessages.keycode(action,
                KeyboardCodes.keycodeByCode.getValue(code), metaState = AndroidKeys.META_SHIFT_ON), messages[action], code)
        }
    }

    @Test
    fun ctrlAndCommandPasteTakePriorityOverKeycodeInjection() {
        for (meta in listOf(AndroidKeys.META_CTRL_ON, AndroidKeys.META_META_ON)) {
            val messages = route("KeyV", "V", meta)
            assertContentEquals(ControlMessages.setClipboard("paste me", true), messages.first())
            // React still routes the shortcut's key-up through the control-key branch.
            assertContentEquals(ControlMessages.keycode(1, 50, metaState = meta), messages.last())
        }
    }

    @Test
    fun menuShortcutSendsImmediatePairAndOtherCombosUseKeycodes() {
        val messages = route("KeyM", "m", AndroidKeys.META_CTRL_ON)
        assertEquals(3, messages.size)
        assertContentEquals(ControlMessages.keycode(0, 82), messages[0])
        assertContentEquals(ControlMessages.keycode(1, 82), messages[1])
        val ctrlA = route("KeyA", "a", AndroidKeys.META_CTRL_ON)
        assertContentEquals(ControlMessages.keycode(0, 29, metaState = 4096), ctrlA[0])
        assertContentEquals(ControlMessages.keycode(1, 29, metaState = 4096), ctrlA[1])
    }

    @Test
    fun emptyClipboardDoesNotInjectVAndHostBackIsNotForwarded() {
        val messages = mutableListOf<ByteArray>()
        KeyInput.route(50, "v", true, 4096, { "" }) { messages += it }
        assertEquals(0, messages.size)
        assertFalse(KeyInput.route(4, null, true, 0, { error("must not read clipboard") }) { messages += it })
        assertEquals(0, messages.size)
    }

    private fun route(code: String, text: String?, meta: Int): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        for (down in listOf(true, false)) {
            KeyInput.route(KeyboardCodes.keycodeByCode.getValue(code), text, down, meta, { "paste me" }) { messages += it }
        }
        return messages
    }
}

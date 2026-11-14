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

class KeyInputTest {
    @Test
    fun printableCharactersOnlyInjectTextOnKeyDown() {
        for ((code, text) in listOf("KeyA" to "a", "KeyA" to "A", "KeyE" to "é", "Space" to " ")) {
            val messages = route(code, text, 0)
            assertEquals(1, messages.size, text)
            assertContentEquals(ControlMessages.text(text), messages.single())
        }
    }

    @Test
    fun controlKeysInjectDownAndUpWithReactMetaFlags() {
        val codes = listOf("Backspace", "Delete", "Enter", "Tab", "Escape", "Home", "End",
            "PageUp", "PageDown", "Insert", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
            "NumpadEnter", "F1", "F12", "ShiftLeft", "ControlRight", "AltLeft", "MetaRight")
        for (code in codes) {
            val messages = route(code, null, AndroidKeys.META_SHIFT_ON)
            assertEquals(2, messages.size, code)
            for (action in 0..1) assertContentEquals(ControlMessages.keycode(action,
                KeyboardCodes.keycodeByCode.getValue(code), metaState = AndroidKeys.META_SHIFT_ON),
                messages[action], code)
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

    @Test
    fun unmappedKeysAreNotConsumedEvenWithModifiersOrPrintableText() {
        for (keycode in listOf(4, 0, 24, 25, 79, 27, 23, 96, 85)) {
            for (meta in listOf(0, AndroidKeys.META_CTRL_ON)) {
                assertFalse(KeyInput.route(keycode, "x", true, meta, { error("clipboard") },
                    send = { error("unmapped input sent") }))
            }
        }
    }

    @Test
    fun singleCharacterHardwareTextMatchesReactEvenForControlCharacters() {
        for (text in listOf("\n", "\t", "\u0000", "\u007f")) {
            val messages = route("KeyA", text, 0)
            assertContentEquals(ControlMessages.text(text), messages.single())
        }
    }

    @Test
    fun mappedFallbackKeysSendBothEdgesWithoutText() {
        for (text in listOf(null, "", "Dead")) {
            val messages = route("KeyA", text, 0)
            assertEquals(2, messages.size)
            assertContentEquals(ControlMessages.keycode(0, 29), messages[0])
            assertContentEquals(ControlMessages.keycode(1, 29), messages[1])
        }
    }

    @Test
    fun onlyReactMetaFlagsAreSentAndCapsLockXorsShiftForLetters() {
        for (capsLock in listOf(false, true)) for (shift in listOf(false, true)) {
            val messages = mutableListOf<ByteArray>()
            val meta = AndroidKeys.META_CTRL_ON or (if (shift) AndroidKeys.META_SHIFT_ON else 0) or
                0x100000 or 0x40 // platform lock and directional flags must not leak
            KeyInput.route(29, "a", true, meta, { null }, capsLock) { messages += it }
            val expected = AndroidKeys.META_CTRL_ON or
                (if (shift != capsLock) AndroidKeys.META_SHIFT_ON else 0)
            assertContentEquals(ControlMessages.keycode(0, 29, metaState = expected), messages.single())
        }
        val messages = mutableListOf<ByteArray>()
        KeyInput.route(66, null, true, 0x100000, { null }, capsLock = true) { messages += it }
        assertContentEquals(ControlMessages.keycode(0, 66), messages.single())
    }

    private fun route(code: String, text: String?, meta: Int): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        for (down in listOf(true, false)) {
            KeyInput.route(KeyboardCodes.keycodeByCode.getValue(code), text, down, meta, { "paste me" }) {
                messages += it
            }
        }
        return messages
    }
}

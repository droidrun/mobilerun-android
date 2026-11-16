// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AndroidKeys
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.InternalStreamApi
import ai.mobilerun.stream.protocol.KeyboardCodes

/** Conformance keyboard routing, with the platform providing its Android keycode and printable text. */
@InternalStreamApi
public class KeyInput {
    private val pressed = mutableMapOf<String, Int>()

    /** Returns false for keys with no remote meaning. Clipboard is read only for the paste shortcut. */
    public fun route(
        keycode: Int,
        text: String?,
        down: Boolean,
        metaState: Int,
        clipboard: () -> String?,
        capsLock: Boolean = false,
        send: (ByteArray) -> Unit,
    ): Boolean {
        val code = codeByKeycode[keycode] ?: return false
        // Native produced controls are not printable text. Abstract vectors retain their key semantics.
        return route(code, text?.takeUnless { value -> value.any { it.isISOControl() } },
            down, metaState, clipboard, capsLock, send)
    }

    /** Routes produced text and physical identity separately, retaining balanced keycode releases. */
    public fun route(
        code: String,
        text: String?,
        down: Boolean,
        metaState: Int,
        clipboard: () -> String?,
        capsLock: Boolean = false,
        send: (ByteArray) -> Unit,
    ): Boolean {
        val keycode = KeyboardCodes.keycodeByCode[code]
        val key = text ?: code
        var meta = metaState and (AndroidKeys.META_SHIFT_ON or AndroidKeys.META_ALT_ON or
            AndroidKeys.META_CTRL_ON or AndroidKeys.META_META_ON)
        if (capsLock && keycode in letterKeycodes) meta = meta xor AndroidKeys.META_SHIFT_ON
        if (!down) {
            val released = pressed.remove(code) ?: return false
            send(ControlMessages.keycode(AndroidKeys.ACTION_UP, released, metaState = meta))
            return true
        }
        val shortcut = meta and (AndroidKeys.META_CTRL_ON or AndroidKeys.META_META_ON) != 0
        when {
            shortcut && key.lowercase() == "v" ->
                clipboard()?.takeIf { it.isNotEmpty() }?.let {
                    send(ControlMessages.setClipboard(it, paste = true))
                }
            shortcut && key.lowercase() == "m" -> press(AndroidKeys.MENU, send)
            !shortcut && code !in controlCodes && !(key.startsWith("F") && key.length <= 3) &&
                key.isNotEmpty() &&
                (key.length == 1 || key.none { it.isISOControl() } && grapheme.matches(key)) -> send(ControlMessages.text(key))
            else -> {
                if (keycode == null) return false
                pressed[code] = keycode
                send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode, metaState = meta))
            }
        }
        return true
    }

    /** Discards physical key state when the host loses focus or control. */
    public fun reset(): Unit = pressed.clear()

    @InternalStreamApi
    public companion object {
        private val controlCodes = listOf(
            "Backspace", "Delete", "Enter", "NumpadEnter", "Tab", "Escape", "Home", "End",
            "PageUp", "PageDown", "Insert",
            "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "ShiftLeft", "ShiftRight", "ControlLeft",
            "ControlRight", "AltLeft", "AltRight", "MetaLeft", "MetaRight",
        ).toSet()
        // The JVM/Android regex engine implements extended grapheme clusters (UAX #29).
        private val grapheme = Regex("\\X")
        private val letterKeycodes = KeyboardCodes.keycodeByCode.filterKeys {
            it.length == 4 && it >= "KeyA" && it <= "KeyZ"
        }.values.toSet()

        private val codeByKeycode = KeyboardCodes.keycodeByCode.entries
            .associate { (code, keycode) -> keycode to code }

        /** A complete press, used for explicit system keys and IME editing actions. */
        public fun press(keycode: Int, send: (ByteArray) -> Unit) {
            send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode))
            send(ControlMessages.keycode(AndroidKeys.ACTION_UP, keycode))
        }
    }
}

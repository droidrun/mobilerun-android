// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AndroidKeys
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.KeyboardCodes

/** React's keyboard routing, with the platform providing its Android keycode and printable text. */
@InternalStreamApi
public class KeyInput {
    private val controlCodes = listOf(
        "Backspace", "Delete", "Enter", "NumpadEnter", "Tab", "Escape", "Home", "End",
        "PageUp", "PageDown", "Insert",
        "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "ShiftLeft", "ShiftRight", "ControlLeft",
        "ControlRight", "AltLeft", "AltRight", "MetaLeft", "MetaRight",
    ).toSet()
    private val pressed = mutableMapOf<String, Int>()
    // The JVM/Android regex engine implements extended grapheme clusters (UAX #29).
    private val grapheme = Regex("\\X")
    private val letterKeycodes = KeyboardCodes.keycodeByCode.filterKeys {
        it.length == 4 && it >= "KeyA" && it <= "KeyZ"
    }.values.toSet()

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
        val code = KeyboardCodes.keycodeByCode.entries.firstOrNull { it.value == keycode }?.key ?: return false
        return route(code, text, down, metaState, clipboard, capsLock, send)
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
            shortcut || key in controlCodes || key.startsWith("Arrow") ||
                key.startsWith("Shift") || key.startsWith("Control") || key.startsWith("Alt") ||
                key.startsWith("Meta") || key.startsWith("F") && key.length <= 3 -> {
                if (keycode == null) return false
                pressed[code] = keycode
                send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode, metaState = meta))
            }
            key.length == 1 || key.isNotEmpty() && key.none { it.isISOControl() } && grapheme.matches(key) ->
                send(ControlMessages.text(key))
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
        /** A complete press, used for explicit system keys and IME editing actions. */
        public fun press(keycode: Int, send: (ByteArray) -> Unit) {
            send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode))
            send(ControlMessages.keycode(AndroidKeys.ACTION_UP, keycode))
        }
    }
}

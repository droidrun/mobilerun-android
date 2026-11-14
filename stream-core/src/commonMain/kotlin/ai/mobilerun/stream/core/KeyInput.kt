// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AndroidKeys
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.KeyboardCodes

/** React's keyboard routing, with the platform providing its Android keycode and printable text. */
@InternalStreamApi
public object KeyInput {
    private val controlCodes = listOf(
        "Backspace", "Delete", "Enter", "NumpadEnter", "Tab", "Escape", "Home", "End",
        "PageUp", "PageDown", "Insert",
        "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "ShiftLeft", "ShiftRight", "ControlLeft",
        "ControlRight", "AltLeft", "AltRight", "MetaLeft", "MetaRight",
    ).toSet()
    private val mappedCodes = KeyboardCodes.keycodeByCode.values.toSet()
    private val controlKeycodes = KeyboardCodes.keycodeByCode.filterKeys {
        it in controlCodes || it.matches(Regex("F[0-9]{1,2}"))
    }.values.toSet()
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
        if (keycode !in mappedCodes) return false
        var meta = metaState and (AndroidKeys.META_SHIFT_ON or AndroidKeys.META_ALT_ON or
            AndroidKeys.META_CTRL_ON or AndroidKeys.META_META_ON)
        if (capsLock && keycode in letterKeycodes) meta = meta xor AndroidKeys.META_SHIFT_ON
        val shortcut = meta and (AndroidKeys.META_CTRL_ON or AndroidKeys.META_META_ON) != 0
        when {
            down && shortcut && text?.lowercase() == "v" ->
                clipboard()?.takeIf { it.isNotEmpty() }?.let {
                    send(ControlMessages.setClipboard(it, paste = true))
                }
            down && shortcut && text?.lowercase() == "m" -> press(AndroidKeys.MENU, send)
            keycode in controlKeycodes || shortcut -> send(ControlMessages.keycode(
                if (down) AndroidKeys.ACTION_DOWN else AndroidKeys.ACTION_UP, keycode, metaState = meta,
            ))
            text != null && text.length == 1 -> if (down) send(ControlMessages.text(text))
            else -> send(ControlMessages.keycode(
                if (down) AndroidKeys.ACTION_DOWN else AndroidKeys.ACTION_UP, keycode, metaState = meta,
            ))
        }
        return true
    }

    /** A complete press, used for explicit system keys and IME editing actions. */
    public fun press(keycode: Int, send: (ByteArray) -> Unit) {
        send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode))
        send(ControlMessages.keycode(AndroidKeys.ACTION_UP, keycode))
    }
}

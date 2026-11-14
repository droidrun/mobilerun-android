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
        "Backspace", "Delete", "Enter", "Tab", "Escape", "Home", "End", "PageUp", "PageDown", "Insert",
        "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "ShiftLeft", "ShiftRight", "ControlLeft",
        "ControlRight", "AltLeft", "AltRight", "MetaLeft", "MetaRight",
    ).plus((1..12).map { "F$it" }).map { KeyboardCodes.keycodeByCode.getValue(it) }.toSet()

    /** Returns false for keys with no remote meaning. Clipboard is read only for the paste shortcut. */
    public fun route(
        keycode: Int,
        text: String?,
        down: Boolean,
        metaState: Int,
        clipboard: () -> String?,
        send: (ByteArray) -> Unit,
    ): Boolean {
        // The host owns Back; forwarding it is always an explicit system-key action.
        if (keycode == AndroidKeys.KEYCODE_BACK || keycode == AndroidKeys.KEYCODE_UNKNOWN) return false
        val shortcut = metaState and (AndroidKeys.META_CTRL_ON or AndroidKeys.META_META_ON) != 0
        if (down && shortcut && text?.lowercase() == "v") {
            clipboard()?.takeIf { it.isNotEmpty() }?.let { send(ControlMessages.setClipboard(it, paste = true)) }
            return true
        }
        if (down && shortcut && text?.lowercase() == "m") {
            press(AndroidKeys.MENU, send)
            return true
        }
        if (keycode in controlCodes || shortcut) {
            send(ControlMessages.keycode(if (down) AndroidKeys.ACTION_DOWN else AndroidKeys.ACTION_UP,
                keycode, metaState = metaState))
        } else if (down && text != null && text.length == 1) {
            send(ControlMessages.text(text))
        } else if (down) {
            send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode, metaState = metaState))
        }
        return true
    }

    /** A complete press, used for explicit system keys and IME editing actions. */
    public fun press(keycode: Int, send: (ByteArray) -> Unit) {
        send(ControlMessages.keycode(AndroidKeys.ACTION_DOWN, keycode))
        send(ControlMessages.keycode(AndroidKeys.ACTION_UP, keycode))
    }
}

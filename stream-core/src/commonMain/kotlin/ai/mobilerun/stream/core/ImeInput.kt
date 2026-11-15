// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AndroidKeys
import ai.mobilerun.stream.protocol.ControlMessages

/** Finalized IME text and marked-composition deletion, shared by native input and fixture replay. */
@InternalStreamApi
public object ImeInput {
    /** Sends printable runs, normalizes CRLF/CR/LF to Enter, and sends Tab as a key press. */
    public fun commit(text: String, send: (ByteArray) -> Unit): Unit =
        commit(text, sendText = { send(ControlMessages.text(it)) }, send = send)

    /** Uses the host's text emitter while retaining shared control-character handling. */
    public fun commit(text: String, sendText: (String) -> Unit, send: (ByteArray) -> Unit) {
        val chunk = StringBuilder()
        fun flush() {
            if (chunk.isNotEmpty()) sendText(chunk.toString())
            chunk.clear()
        }
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            when (character) {
                '\r', '\n', '\t' -> {
                    flush()
                    if (character == '\r' && index < text.length && text[index] == '\n') index++
                    KeyInput.press(if (character == '\t') AndroidKeys.KEYCODE_TAB
                        else AndroidKeys.ENTER, send)
                }
                else -> if (!character.isISOControl()) chunk.append(character)
            }
        }
        flush()
    }

    /** Sends bounded remote deletion after the native composition buffer has been handled locally. */
    public fun delete(before: Int, after: Int, send: (ByteArray) -> Unit) {
        if (before < 0 || after < 0) return
        repeat(before.coerceAtMost(64)) { KeyInput.press(AndroidKeys.DEL, send) }
        repeat(after.coerceAtMost(64)) { KeyInput.press(AndroidKeys.FORWARD_DEL, send) }
    }
}

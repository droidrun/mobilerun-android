// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.AndroidKeys
import ai.mobilerun.stream.protocol.ControlMessages
import ai.mobilerun.stream.protocol.KeyboardCodes

/** Finalized IME text and marked-composition deletion, shared by native input and fixture replay. */
@InternalStreamApi
public object ImeInput {
    /** Sends printable runs, normalizes CRLF/CR/LF to Enter, and sends Tab as a key press. */
    public fun commit(text: String, send: (ByteArray) -> Unit) {
        val chunk = StringBuilder()
        fun flush() {
            if (chunk.isNotEmpty()) send(ControlMessages.text(chunk.toString()))
            chunk.clear()
        }
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            when (character) {
                '\r', '\n', '\t' -> {
                    flush()
                    if (character == '\r' && index < text.length && text[index] == '\n') index++
                    KeyInput.press(if (character == '\t') KeyboardCodes.keycodeByCode.getValue("Tab")
                        else AndroidKeys.ENTER, send)
                }
                else -> if (!character.isISOControl()) chunk.append(character)
            }
        }
        flush()
    }

    /** Marked text is local; an empty marked buffer permits bounded remote deletion. */
    public fun delete(composition: String, before: Int, after: Int, send: (ByteArray) -> Unit) {
        if (composition.isNotEmpty() || before < 0 || after < 0) return
        repeat(before.coerceAtMost(64)) { KeyInput.press(AndroidKeys.DEL, send) }
        repeat(after.coerceAtMost(64)) { KeyInput.press(AndroidKeys.FORWARD_DEL, send) }
    }
}

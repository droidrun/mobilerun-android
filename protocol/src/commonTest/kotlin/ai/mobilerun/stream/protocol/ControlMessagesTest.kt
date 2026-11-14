// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import ai.mobilerun.stream.conformance.fixtureCase
import ai.mobilerun.stream.conformance.toHex
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the encoders beyond the fixture cases. */
class ControlMessagesTest {
    private fun hexOf(file: String, name: String) =
        fixtureCase(file, name).getValue("hex").jsonPrimitive.content

    @Test
    fun omittedArgumentsUseTheReferenceDefaults() {
        assertEquals(
            hexOf("control/touch.json", "mouse defaults only (no buttons)"),
            ControlMessages.touch(
                action = 0,
                pointerId = -1,
                videoWidth = 1080,
                videoHeight = 2400,
                x = 10.0,
                y = 20.0,
            ).toHex(),
        )
        assertEquals(
            hexOf("control/keycode.json", "home down"),
            ControlMessages.keycode(action = 0, keycode = 3).toHex(),
        )
        assertEquals(
            hexOf("control/set-clipboard.json", "ascii sentence, paste true"),
            ControlMessages.setClipboard("Hello, World!").toHex(),
        )
    }

    @Test
    fun unpairedSurrogateEncodesAsReplacementCharacter() {
        assertEquals("0103000000efbfbd", ControlMessages.text("\uD83D").toHex(), "lone high surrogate")
        assertEquals("010500000061efbfbd62", ControlMessages.text("a\uDC00b").toHex(), "lone low surrogate")
        assertEquals("010400000061efbfbd", ControlMessages.text("a\uD83D").toHex(), "high at the end")
        assertEquals("0106000000efbfbdefbfbd", ControlMessages.text("\uDE00\uD83D").toHex(), "reversed pair")
        assertEquals("010500000061f09f9880", ControlMessages.text("a\uD83D\uDE00").toHex(), "valid pair")
    }

    @Test
    fun unpairedSurrogateInClipboardEncodesAsReplacementCharacter() {
        val header = "09" + "0000000000000000"
        val lone = ControlMessages.setClipboard("\uD83D", paste = false)
        assertEquals(header + "00" + "00000003" + "efbfbd", lone.toHex())
        val inner = ControlMessages.setClipboard("a\uDC00b")
        assertEquals(header + "01" + "00000005" + "61efbfbd62", inner.toHex())
    }
}

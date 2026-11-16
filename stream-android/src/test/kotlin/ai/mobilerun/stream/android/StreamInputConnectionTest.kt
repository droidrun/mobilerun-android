// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.protocol.ControlMessages
import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StreamInputConnectionTest {
    private val messages = mutableListOf<ByteArray>()
    private var active = true
    private var keyEvents = 0
    private val connection = StreamInputConnection(View(RuntimeEnvironment.getApplication()),
        active = { active }, send = { messages += it }, key = {
            keyEvents++
            true
        })

    @Test
    fun compositionIsLocalAndOnlyCommittedTextIsSent() {
        connection.setComposingText("hel", 1)
        connection.setComposingText("hello", 1)
        assertTrue(messages.isEmpty())
        connection.commitText("hello", 1)
        assertContentEquals(ControlMessages.text("hello"), messages.single())
        assertTrue(connection.editable.isEmpty())
        connection.finishComposingText()
        assertEquals(1, messages.size)
    }

    @Test
    fun finishingCompositionCommitsThenBackspaceDeletesRemoteText() {
        connection.setComposingText("hello", 1)
        connection.finishComposingText()
        connection.deleteSurroundingText(1, 0)
        messages.assertSent(ControlMessages.text("hello"),
            ControlMessages.keycode(0, 67), ControlMessages.keycode(1, 67))
        assertTrue(connection.editable.isEmpty())
        connection.finishComposingText()
        assertEquals(3, messages.size)
    }

    @Test
    fun successiveCommitsKeepEditingKeys() {
        // Exercise successive commits, redundant finalization and editing keys.
        assertTrue(connection.editable.isEmpty())
        connection.finishComposingText()
        connection.commitText("q", 1)
        connection.commitText(" ", 1)
        connection.commitText("👍", 1)
        connection.finishComposingText()
        connection.deleteSurroundingText(1, 0)
        connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
        messages.assertSent(
            ControlMessages.text("q"), ControlMessages.text(" "),
            ControlMessages.text("👍"),
            ControlMessages.keycode(0, 67), ControlMessages.keycode(1, 67),
            ControlMessages.keycode(0, 66), ControlMessages.keycode(1, 66),
        )
    }

    @Test
    fun cjkCompositionCommitsOnceAndMixedUnicodeControlsStayKeys() {
        connection.setComposingText("zhong", 1)
        connection.setComposingText("中文", 1)
        assertTrue(messages.isEmpty())
        connection.commitText("中文", 1)
        connection.finishComposingText()
        connection.commitText("👋\r\n中文\tend", 1)
        messages.assertSent(
            ControlMessages.text("中文"),
            ControlMessages.text("👋"),
            ControlMessages.keycode(0, 66), ControlMessages.keycode(1, 66),
            ControlMessages.text("中文"),
            ControlMessages.keycode(0, 61), ControlMessages.keycode(1, 61),
            ControlMessages.text("end"),
        )
    }

    @Test
    fun committedUnicodeRunMatchesSharedFixtureExactlyOnce() {
        connection.commitText("é中😀é", 1)
        connection.finishComposingText()
        // Literal INJECT_TEXT bytes from conformance v0.2.0 input/ime.json, "unicode".
        assertEquals(listOf("010c000000c3a9e4b8adf09f988065cc81"), messages.map { bytes ->
            bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        })
    }

    @Test
    fun largeDeleteRequestsAreBounded() {
        connection.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(256, messages.size)
        assertFalse(connection.deleteSurroundingText(-1, 0))
    }

    @Test
    fun backspaceWithEmptyBufferAndEditorActionSendKeyPairs() {
        connection.deleteSurroundingText(1, 0)
        connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
        val expected = listOf(ControlMessages.keycode(0, 67), ControlMessages.keycode(1, 67),
            ControlMessages.keycode(0, 66), ControlMessages.keycode(1, 66))
        assertEquals(expected.size, messages.size)
        expected.zip(messages).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test
    fun codePointDeletionAndNewlineCommitUseRemoteEditingKeys() {
        connection.deleteSurroundingTextInCodePoints(1, 1)
        connection.commitText("one\ntwo", 1)
        assertEquals(8, messages.size)
        assertContentEquals(ControlMessages.keycode(0, 112), messages[2])
        assertContentEquals(ControlMessages.text("one"), messages[4])
        assertContentEquals(ControlMessages.keycode(0, 66), messages[5])
        assertContentEquals(ControlMessages.text("two"), messages[7])
    }

    @Test
    fun deletingCompositionDoesNotDeleteRemoteText() {
        connection.setComposingText("marked", 1)
        connection.deleteSurroundingText(1, 0)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun staleOrClosedConnectionCannotSendInput() {
        active = false
        assertFalse(connection.commitText("stale", 1))
        assertFalse(connection.deleteSurroundingText(1, 0))
        assertFalse(connection.performEditorAction(EditorInfo.IME_ACTION_GO))
        assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(messages.isEmpty())
        assertEquals(0, keyEvents)
        active = true
        connection.closeConnection()
        assertFalse(connection.commitText("closed", 1))
    }

    @Test
    fun imeKeyEventsGoThroughHardwareRouting() {
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        assertEquals(1, keyEvents)
    }
    @Test
    fun committedControlsUseEditingKeysAndDropOtherControls() {
        connection.commitText("a\tb\nc\r\u0000\u001b\u007fd", 1)
        messages.assertSent(
            ControlMessages.text("a"),
            ControlMessages.keycode(0, 61), ControlMessages.keycode(1, 61),
            ControlMessages.text("b"),
            ControlMessages.keycode(0, 66), ControlMessages.keycode(1, 66),
            ControlMessages.text("c"),
            ControlMessages.keycode(0, 66), ControlMessages.keycode(1, 66),
            ControlMessages.text("d"),
        )
    }

}

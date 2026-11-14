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
        active = { active }, send = { messages += it }, key = { keyEvents++; true })

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
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.KeyInput
import ai.mobilerun.stream.core.ImeInput
import ai.mobilerun.stream.protocol.AndroidKeys
import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo

/** Local composition only; the remote editor receives committed text, never marked text. */
internal class StreamInputConnection(
    view: View,
    private val active: () -> Boolean,
    private val send: (ByteArray) -> Unit,
    private val key: (KeyEvent) -> Boolean,
) : BaseInputConnection(view, true) {
    private val buffer = SpannableStringBuilder()
    private var closed = false
    private fun canSend() = !closed && active()

    override fun getEditable(): Editable = buffer

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        canSend() && super.setComposingText(text, newCursorPosition)

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!canSend()) return false
        ImeInput.commit(text?.toString().orEmpty(), send)
        buffer.clear()
        removeComposingSpans(buffer)
        return true
    }

    override fun finishComposingText(): Boolean {
        if (!canSend()) return false
        return commitText(buffer.toString(), 1)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!canSend() || beforeLength < 0 || afterLength < 0) return false
        if (buffer.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
        ImeInput.delete(buffer.toString(), beforeLength, afterLength, send)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (!canSend() || beforeLength < 0 || afterLength < 0) return false
        return if (buffer.isNotEmpty()) super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        else deleteSurroundingText(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = canSend() && key(event)

    override fun performEditorAction(actionCode: Int): Boolean {
        if (!canSend()) return false
        when (actionCode) {
            EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS ->
                KeyInput.press(AndroidKeys.ENTER, send)
            else -> return false
        }
        return true
    }

    override fun closeConnection() {
        closed = true
        buffer.clear()
        super.closeConnection()
    }
}

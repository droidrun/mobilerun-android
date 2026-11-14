// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import android.app.Activity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceStreamViewTest {
    @Test
    fun noSessionHasNoVideoOrInputClientAndDetachIsIdempotent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = DeviceStreamView(controller.get())
        controller.get().setContentView(view)
        assertNull(view.renderedVideoRect)
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertFalse(view.onCheckIsTextEditor())
        view.showKeyboard()
        view.detach()
        view.detach()
        controller.pause().stop().destroy()
    }

    @Test
    fun displayOnlyViewHasNoInputClientAndBackRemainsLocal() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = DeviceStreamSession(activity, { null }, scope)
        // Attach before a window exists: tests do not load a native renderer or connect a device.
        val view = DeviceStreamView(activity)
        view.attach(session)
        assertTrue(view.onCheckIsTextEditor())
        view.hasControl = false
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertFalse(view.onCheckIsTextEditor())
        view.hasControl = true
        assertFalse(view.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        view.detach()
        session.close()
        scope.cancel()
        controller.pause().stop().destroy()
    }
}

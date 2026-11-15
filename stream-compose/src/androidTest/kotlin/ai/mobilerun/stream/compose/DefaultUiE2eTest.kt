// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.android.StreamConfig
import ai.mobilerun.stream.android.DeviceStreamView
import ai.mobilerun.stream.core.*
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.os.SystemClock
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only sample screens; runtime credentials are injected into app-private storage by the runner. */
class DefaultUiE2eTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "e2e-out").also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<DeviceStreamSession>()
    private var root: View? = null

    private fun findStream(view: View): DeviceStreamView? {
        if (view is DeviceStreamView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findStream(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun streamView(): DeviceStreamView = assertNotNull(findStream(assertNotNull(root)))

    private suspend fun tap(session: DeviceStreamSession, nx: Double, ny: Double) {
        compose.runOnIdle {
            val view = streamView()
            val geometry = assertNotNull(InputGeometry.fit(view.width, view.height, session.videoSize.value))
            val x = (geometry.left + nx * geometry.width).toFloat()
            val y = (geometry.top + ny * geometry.height).toFloat()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }
        delay(1500)
    }

    private suspend fun remoteShot(session: DeviceStreamSession, name: String): IntArray {
        val bytes = session.screenshot()
        File(output, "$name-device.png").writeBytes(bytes)
        shot(name)
        val bitmap = assertNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val width = bitmap.width
        val top = (bitmap.height * .04).toInt()
        val height = (bitmap.height * .86).toInt()
        return IntArray(width * height).also {
            bitmap.getPixels(it, 0, width, 0, top, width, height)
            bitmap.recycle()
        }
    }

    @After fun cleanup() {
        sessions.forEach { it.close() }
        scope.cancel()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val bitmap = assertNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun liveVideoAndCredentialRecovery() = runBlocking {
        val json = JSONObject(File(context.filesDir, "stream-e2e.json").readText())
        val credentials = StreamCredentials(json.getString("streamUrl"), json.getString("streamToken"))
        val rejected = AtomicInteger()
        val expired = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<Call>()
        // WebSocket calls suppress EventListener; an application interceptor retains the real call.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.call()
            chain.proceed(chain.request())
        }.build()
        val provider = { if (expired.get()) StreamCredentials(credentials.url, "expired") else credentials }
        var session by mutableStateOf(DeviceStreamSession(context, provider, scope,
            StreamConfig(httpClient = client, logger = { line ->
                if (!expired.get() && (line.contains("http=401") || line.contains("http=404"))) rejected.set(1)
            })).also { sessions += it })
        var tokens by mutableStateOf(StreamThemeTokens.light())
        compose.setContent {
            val host = LocalView.current
            SideEffect { root = host.rootView }
            StreamTheme(tokens) { DeviceStream(session, Modifier.fillMaxSize()) }
        }
        shot("live-connecting")
        session.start()
        withTimeout(60_000) {
            while (session.status.value != StreamStatus.Connected || session.videoSize.value == null) {
                check(rejected.get() == 0) { "PENDING: device HTTP 401/404" }
                delay(100)
            }
        }
        delay(2000)
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
        shot("live-connected-navigation")
        compose.onNodeWithContentDescription("Home").performClick()
        delay(1500)
        val home = remoteShot(session, "live-home")
        // Existing Settings launcher icon and Settings search, normalized to the upright picture.
        tap(session, .15, .895)
        val settings = remoteShot(session, "live-tap-settings")
        assertFalse(home.contentEquals(settings), "tap opens Settings through the default UI")
        tap(session, .5, .055)
        val field = remoteShot(session, "live-search-field")
        compose.runOnIdle {
            streamView().showKeyboard()
        }
        delay(500)
        compose.runOnIdle {
            val view = streamView()
            assertTrue(view.hasFocus(), "stream owns keyboard focus")
            val input = assertNotNull(view.onCreateInputConnection(EditorInfo()))
            assertTrue(input.setComposingText("display", 1))
            assertTrue(input.finishComposingText())
        }
        delay(1500)
        compose.runOnIdle { streamView().hideKeyboard() }
        delay(500)
        val typed = remoteShot(session, "live-typed")
        assertFalse(field.contentEquals(typed), "committed text changes Settings search")
        compose.onNodeWithContentDescription("Back").performClick()
        delay(1000)
        remoteShot(session, "live-back")
        compose.onNodeWithContentDescription("Home").performClick()
        delay(1000)
        remoteShot(session, "live-home-returned")
        compose.onNodeWithContentDescription("Recent").performClick()
        delay(1000)
        remoteShot(session, "live-recent")
        compose.onNodeWithContentDescription("Home").performClick()
        val oldCalls = calls.size
        calls.last().cancel()
        withTimeout(30_000) { session.status.first { it is StreamStatus.Reconnecting } }
        shot("live-forced-reconnecting")
        withTimeout(60_000) { session.status.first { it == StreamStatus.Connected } }
        withTimeout(20_000) { session.videoSize.first { it != null } }
        assertTrue(calls.size > oldCalls, "forced socket cancellation opens a fresh connection")
        remoteShot(session, "live-forced-recovered")
        compose.runOnIdle { tokens = StreamThemeTokens.dark() }
        shot("live-dark")
        session.close()
        expired.set(true)
        val recovering = DeviceStreamSession(context, provider, scope).also { sessions += it }
        compose.runOnIdle { session = recovering }
        recovering.start()
        shot("live-expired-start")
        withTimeout(160_000) { recovering.status.first { it == StreamStatus.Unavailable } }
        shot("live-unavailable-observed")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("device stream unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        shot("live-unavailable-expired")
        compose.onNodeWithText("device stream unavailable").assertIsDisplayed()
        expired.set(false)
        compose.onNodeWithText("Retry").performClick()
        withTimeout(60_000) { recovering.status.first { it == StreamStatus.Connected } }
        withTimeout(20_000) { recovering.videoSize.first { it != null } }
        delay(2000)
        shot("live-retry-recovered")
    }
}

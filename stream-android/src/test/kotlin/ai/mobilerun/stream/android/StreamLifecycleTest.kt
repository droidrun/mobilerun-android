// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.*
import android.app.Application
import android.net.ConnectivityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StreamLifecycleTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private class Harness(scope: TestScope) {
        val context = RuntimeEnvironment.getApplication()
        val owner = Owner()
        val registry get() = owner.lifecycle
        val listeners = mutableListOf<SignallingListener>()
        val network = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val session = DeviceStreamSession(context, { StreamCredentials("wss://example.test", "temporary") },
            scope.backgroundScope, StreamConfig(), SignallingTransportFactory { _, listener ->
                listeners += listener
                object : SignallingTransport {
                    override fun send(text: String) = true
                    override fun close() = Unit
                }
            }, PeerTransportFactory { _, _, _ -> error("no negotiation in this test") })
    }

    @Test fun resumedRegistrationDoesNotWakeButStopStartDoes() = runTest {
        val h = Harness(this)
        h.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        h.session.start()
        runCurrent()
        h.listeners.last().onFailure(null)
        runCurrent()
        val adapter = StreamLifecycle(h.context, h.session, h.registry)
        runCurrent()
        assertEquals(1, h.listeners.size)
        h.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        h.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals(2, h.listeners.size)
        adapter.close()
    }

    @Test fun networkRegistrationIsIgnoredAndOnlyLossRecoveryWakes() = runTest {
        val h = Harness(this)
        val adapter = StreamLifecycle(h.context, h.session, h.registry)
        val callback = h.network.networkCallbacks.single()
        h.session.start()
        runCurrent()
        h.listeners.last().onFailure(null)
        runCurrent()
        callback.onAvailable(ShadowNetwork.newInstance(1))
        runCurrent()
        assertEquals(1, h.listeners.size)
        callback.onLost(ShadowNetwork.newInstance(1))
        callback.onAvailable(ShadowNetwork.newInstance(2))
        runCurrent()
        assertEquals(2, h.listeners.size)
        callback.onAvailable(ShadowNetwork.newInstance(2))
        runCurrent()
        assertEquals(2, h.listeners.size)
        adapter.close()
        adapter.close()
        assertTrue(h.network.networkCallbacks.isEmpty())
        h.session.close()
        callback.onLost(ShadowNetwork.newInstance(2))
        callback.onAvailable(ShadowNetwork.newInstance(3))
        runCurrent()
        assertEquals(2, h.listeners.size)
    }

    @Implements(ConnectivityManager::class)
    class FailingConnectivity : ShadowConnectivityManager() {
        @Implementation
        override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            throw IllegalStateException("registration failed")
        }
    }

    @Test
    @Config(shadows = [FailingConnectivity::class])
    fun failedRegistrationRemovesLifecycleObserver() = runTest {
        val h = Harness(this)
        h.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val before = h.registry.observerCount
        assertFailsWith<IllegalStateException> { StreamLifecycle(h.context, h.session, h.registry) }
        assertEquals(before, h.registry.observerCount)
    }
    @Test fun startAfterCloseNeverPrewarmsOrFetchesCredentials() = runTest {
        var prewarms = 0
        var fetches = 0
        val session = DeviceStreamSession(RuntimeEnvironment.getApplication(), {
            fetches++
            StreamCredentials("wss://stream.example.test", "test")
        }, backgroundScope, StreamConfig(), SignallingTransportFactory { _, _ -> error("closed") },
            prewarm = { prewarms++ })
        session.close()
        session.start()
        runCurrent()
        assertEquals(0, prewarms)
        assertEquals(0, fetches)
    }

}

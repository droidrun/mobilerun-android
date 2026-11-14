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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StreamLifecycleTest {
    @Test fun foregroundAndNetworkWakeAndCloseUnregisters() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val registry = LifecycleRegistry(object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = error("registry supplies events")
        })
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val listeners = mutableListOf<SignallingListener>()
        val session = DeviceStreamSession(context, { StreamCredentials("wss://example.test", "temporary") },
            backgroundScope, StreamConfig(), SignallingTransportFactory { _, listener ->
                listeners += listener
                object : SignallingTransport {
                    override fun send(text: String) = true
                    override fun close() = Unit
                }
            }, PeerTransportFactory { _, _, _ -> error("no negotiation in this test") })
        val network = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val adapter = StreamLifecycle(context, session, registry)
        assertEquals(1, network.networkCallbacks.size)
        val callback = network.networkCallbacks.single()
        session.start(); runCurrent()
        listeners.last().onFailure(null); runCurrent()
        assertEquals(1, listeners.size)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START); runCurrent()
        assertEquals(2, listeners.size)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP); runCurrent()
        listeners.last().onFailure(null); runCurrent()
        callback.onAvailable(ShadowNetwork.newInstance(1)); runCurrent()
        assertEquals(3, listeners.size)
        adapter.close(); adapter.close()
        assertTrue(network.networkCallbacks.isEmpty())
        session.close(); runCurrent()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        callback.onAvailable(ShadowNetwork.newInstance(2)); runCurrent()
        assertEquals(3, listeners.size)
    }
}

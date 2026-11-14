// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Process visibility and network wake adapter. Create/close on the main thread. Backgrounding
 * pauses keep-alive; foreground and a newly available network wake pending recovery. Closing the
 * adapter removes both observers. It never starts or closes the caller-owned session.
 *
 * Custom View hosts can use this directly; Compose DeviceStream installs it automatically.
 */
public class StreamLifecycle internal constructor(
    context: Context,
    session: DeviceStreamSession,
    private val lifecycle: Lifecycle,
) : AutoCloseable {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    /** Observes process visibility and the default network for [session]. */
    public constructor(context: Context, session: DeviceStreamSession) :
        this(context, session, ProcessLifecycleOwner.get().lifecycle)

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> { session.setHostVisible(true); session.handleWake() }
            Lifecycle.Event.ON_STOP -> session.setHostVisible(false)
            else -> Unit
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { session.handleWake() }
    }
    private var closed = false

    init {
        session.setHostVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        connectivity.registerDefaultNetworkCallback(callback)
    }

    /** Removes the observers; does not change session ownership. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(observer)
        connectivity.unregisterNetworkCallback(callback)
    }
}

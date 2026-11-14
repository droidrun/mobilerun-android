// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process visibility and network wake adapter; create/close on the main thread. Registration's
 * initial lifecycle/network notifications do not wake recovery. Actual stop/start and lost/available
 * transitions wake it. This adapter never starts or closes the caller-owned session.
 */
public class StreamLifecycle internal constructor(
    context: Context,
    session: DeviceStreamSession,
    private val lifecycle: Lifecycle,
) : AutoCloseable {
    /** Observes process visibility and default-network transitions for [session]. */
    public constructor(context: Context, session: DeviceStreamSession) :
        this(context, session, ProcessLifecycleOwner.get().lifecycle)

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networkLost = AtomicBoolean(false)
    @Volatile private var closed = false
    private var stopped = false
    private val observer = LifecycleEventObserver { _, event ->
        if (!closed) when (event) {
            Lifecycle.Event.ON_START -> {
                session.setHostVisible(true)
                if (stopped) {
                    stopped = false
                    session.handleWake()
                }
            }
            Lifecycle.Event.ON_STOP -> {
                stopped = true
                session.setHostVisible(false)
            }
            else -> Unit
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            networkLost.set(true)
        }

        override fun onAvailable(network: Network) {
            if (!closed && networkLost.getAndSet(false)) session.handleWake()
        }
    }

    init {
        session.setHostVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (failure: RuntimeException) {
            closed = true
            lifecycle.removeObserver(observer)
            throw failure
        }
    }

    /** Removes both observers. Idempotent; does not change session ownership. */
    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(observer)
        connectivity.unregisterNetworkCallback(callback)
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.StreamStatus
import android.content.Intent
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live lifecycle/network recovery, bounded ten-minute soak and release checks. */
class StreamHardeningTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun memoryKb(): Int {
        System.gc()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    private suspend fun exercise(): WeakReference<DeviceStreamSession> {
        val device = E2eDevice.load(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger()
        val factory = ai.mobilerun.stream.core.SignallingTransportFactory { credentials, listener ->
            opens.incrementAndGet()
            device.signalling.open(credentials, listener)
        }
        val session = DeviceStreamSession(context, { device.credentials }, scope, StreamConfig(), factory)
        var host: InputTestActivity? = null
        var lifecycle: StreamLifecycle? = null
        val peersBefore = WebRtcRuntime.activePeers.get()
        try {
            session.start()
            device.awaitConnectedWithVideo(session)
            host = instrumentation.startActivitySync(Intent(context, InputTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputTestActivity
            val activity = host
            ui {
                activity.stream.attach(session)
                lifecycle = StreamLifecycle(context, session)
            }
            withTimeout(15_000) { session.stats.first { it != null } }

            // Exercise actual process visibility and SurfaceView destruction/recreation.
            shell("input keyevent KEYCODE_HOME")
            delay(2000)
            instrumentation.startActivitySync(Intent(context, InputTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            device.awaitConnectedWithVideo(session)
            withTimeout(15_000) { session.stats.first { it != null } }

            // Both emulator networks are disabled, then restored even if an assertion fails.
            try {
                shell("svc wifi disable")
                shell("svc data disable")
                delay(15_000)
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
            }
            device.awaitConnectedWithVideo(session)
            withTimeout(15_000) { session.stats.first { it != null } }

            // Warm up allocations first; then record one sample per minute for ten minutes.
            delay(30_000)
            val start = memoryKb()
            val samples = mutableListOf(start)
            repeat(10) {
                delay(60_000)
                assertEquals(StreamStatus.Connected, session.status.value, "soak connection")
                assertTrue(session.stats.value != null)
                samples += memoryKb()
            }
            val end = samples.last()
            val bound = maxOf(32 * 1024, start / 4)
            File(context.filesDir, "e2e-out/hardening.json").also { it.parentFile?.mkdirs() }.writeText(
                JSONObject().put("soakSeconds", 600).put("startPssKb", start).put("endPssKb", end)
                    .put("allowedGrowthKb", bound).put("samplesPssKb", org.json.JSONArray(samples))
                    .put("connectionsOpened", opens.get()).toString(2))
            assertTrue(end - start <= bound, "PSS growth exceeds max(32 MiB, 25% of baseline)")
        } finally {
            ui {
                lifecycle?.close()
                host?.stream?.detach()
                host?.finish()
            }
            session.close()
            scope.cancel()
            withTimeout(10_000) {
                while (session.stats.value != null || session.videoSize.value != null ||
                    WebRtcRuntime.activePeers.get() != peersBefore) delay(50)
            }
            assertFalse(session.sendControl(byteArrayOf(0)))
            assertFalse(session.sendHover(byteArrayOf(0)))
            val closedOpens = opens.get()
            session.start()
            session.retry()
            session.handleWake()
            delay(2000)
            assertEquals(closedOpens, opens.get(), "closed session leaves no reconnect timer")
        }
        return WeakReference(session)
    }

    @Test fun lifecycleNetworkSoakAndClose() = runBlocking {
        val reference = exercise()
        withTimeout(10_000) {
            while (reference.get() != null) {
                System.gc()
                delay(100)
            }
        }
        assertNull(reference.get(), "closed session must be collectable")
    }
}

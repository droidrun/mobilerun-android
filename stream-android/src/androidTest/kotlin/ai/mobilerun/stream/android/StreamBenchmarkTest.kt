// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.protocol.AMotionEvent
import ai.mobilerun.stream.protocol.ControlMessages
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.webrtc.EglRenderer
import org.webrtc.SurfaceViewRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Instrumentation-only example: repeated connections and tap-to-render timing, no bundled secrets. */
class StreamBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private suspend fun renderedFrame(renderer: SurfaceViewRenderer): Bitmap {
        val ready = CompletableDeferred<Bitmap>()
        val listener = EglRenderer.FrameListener { bitmap ->
            if (bitmap != null) {
                if (!ready.complete(bitmap)) bitmap.recycle()
            }
        }
        renderer.addFrameListener(listener, .25f)
        return try {
            withTimeout(10_000) { ready.await() }
        } finally {
            renderer.removeFrameListener(listener)
        }
    }

    /** Mean luma in an explicitly chosen stable region, normalized to the rendered picture. */
    private fun regionLuma(bitmap: Bitmap): Double {
        val region = args.getString("benchmarkRegion")?.split(',')?.map { it.toDouble() }
            ?: listOf(.15, .15, .7, .7)
        require(region.size == 4 && region.all { it.isFinite() && it in 0.0..1.0 } &&
            region[2] > 0 && region[3] > 0 && region[0] + region[2] <= 1 && region[1] + region[3] <= 1)
        val x0 = (bitmap.width * region[0]).toInt()
        val y0 = (bitmap.height * region[1]).toInt()
        val width = (bitmap.width * region[2]).toInt().coerceAtLeast(1)
        val height = (bitmap.height * region[3]).toInt().coerceAtLeast(1)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, x0, y0, width, height)
        return pixels.sumOf { pixel ->
            .2126 * (pixel shr 16 and 255) + .7152 * (pixel shr 8 and 255) + .0722 * (pixel and 255)
        } / pixels.size
    }

    private suspend fun luma(renderer: SurfaceViewRenderer): Double {
        val frame = renderedFrame(renderer)
        return try { regionLuma(frame) } finally { frame.recycle() }
    }

    private suspend fun tapLatency(session: DeviceStreamSession, renderer: SurfaceViewRenderer): Double {
        // Restore the same launcher state before every sample. Choose an existing harmless app icon.
        session.sendControl(ControlMessages.keycode(0, 3))
        session.sendControl(ControlMessages.keycode(1, 3))
        delay(1500)
        val baseline = luma(renderer)
        repeat(4) {
            delay(100)
            assertTrue(abs(luma(renderer) - baseline) < 1.0, "latency region must be stable before input")
        }
        val size = assertNotNull(session.videoSize.value)
        val x = requireNotNull(args.getString("tapX")) { "tapX must select a harmless launcher icon" }.toDouble()
        val y = requireNotNull(args.getString("tapY")) { "tapY must select a harmless launcher icon" }.toDouble()
        require(x in 0.0..size.displayWidth.toDouble() && y in 0.0..size.displayHeight.toDouble())
        // Install a persistent renderer callback BEFORE sending input. The timestamp is taken in
        // that callback, so coroutine scheduling cannot inflate the observed arrival timestamp.
        val changed = CompletableDeferred<Long>()
        val startedNs = AtomicLong(Long.MAX_VALUE)
        val active = AtomicBoolean(true)
        lateinit var listener: EglRenderer.FrameListener
        listener = EglRenderer.FrameListener { bitmap ->
            if (bitmap != null) {
                val frameNs = SystemClock.elapsedRealtimeNanos()
                try {
                    if (active.get() && frameNs >= startedNs.get() && abs(regionLuma(bitmap) - baseline) >= 4.0) {
                        changed.complete(frameNs)
                    }
                } finally { bitmap.recycle() }
                // WebRTC frame listeners are one-shot. Re-arm until the selected region changes.
                if (active.get() && !changed.isCompleted) renderer.addFrameListener(listener, .25f)
            }
        }
        renderer.addFrameListener(listener, .25f)
        try {
            startedNs.set(SystemClock.elapsedRealtimeNanos())
            for (action in listOf(AMotionEvent.ACTION_DOWN, AMotionEvent.ACTION_UP)) {
                assertTrue(session.sendControl(ControlMessages.touch(action, -1, size.displayWidth,
                    size.displayHeight, x, y, 1.0, 1, 1)), "control channel must be open")
            }
            return (withTimeout(10_000) { changed.await() } - startedNs.get()) / 1_000_000.0
        } finally {
            active.set(false)
            renderer.removeFrameListener(listener)
        }
    }

    @Test fun measureConnectionsAndInputToPixel() = runBlocking {
        val output = File(context.filesDir, "e2e-out").also { it.mkdirs() }
        val results = JSONArray()
        val runs = (args.getString("benchmarkRuns") ?: "5").toInt().also { require(it in 1..20) }
        val samples = (args.getString("benchmarkSamples") ?: "10").toInt().also { require(it in 1..100) }
        val device = E2eDevice.load(context)
        repeat(runs) { run ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = DeviceStreamSession(context, { device.credentials }, scope, StreamConfig(), device.signalling)
            var activity: InputTestActivity? = null
            try {
                session.start()
                device.awaitConnectedWithVideo(session)
                activity = instrumentation.startActivitySync(Intent(context, InputTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputTestActivity
                val host = activity
                ui { host.stream.attach(session) }
                delay(3000)
                lateinit var renderer: SurfaceViewRenderer
                ui { renderer = host.stream.getChildAt(0) as SurfaceViewRenderer }
                val steady = mutableListOf<StreamStats>()
                repeat(10) {
                    delay(1000)
                    steady += withTimeout(10_000) { session.stats.first { it?.bitrateKbps != null } }!!
                }
                val latency = List(samples) { tapLatency(session, renderer) }.sorted()
                fun mean(metric: (StreamStats) -> Double?): Double? =
                    steady.mapNotNull(metric).takeIf { it.isNotEmpty() }?.average()
                val last = steady.last()
                val row = JSONObject().put("run", run + 1)
                    .put("firstFrameMs", last.timeToFirstFrameMs ?: JSONObject.NULL)
                    .put("fps", mean { it.fps } ?: JSONObject.NULL)
                    .put("bitrateKbps", mean { it.bitrateKbps } ?: JSONObject.NULL)
                    .put("rttMs", mean { it.rttMs } ?: JSONObject.NULL)
                    .put("candidateType", last.candidateType ?: JSONObject.NULL)
                    .put("latencyMedianMs", if (latency.size % 2 == 0)
                        (latency[latency.size / 2 - 1] + latency[latency.size / 2]) / 2 else latency[latency.size / 2])
                    .put("latencyP90Ms", latency[kotlin.math.ceil(latency.size * .9).toInt() - 1])
                    .put("latencySamplesMs", JSONArray(latency))
                results.put(row)
                File(output, "benchmark.json").writeText(results.toString(2))
            } finally {
                activity?.let { host -> ui { host.stream.detach(); host.finish() } }
                session.close()
                withTimeout(10_000) {
                    while (session.videoSize.value != null || session.stats.value != null) delay(50)
                }
                assertTrue(!session.sendControl(byteArrayOf(0)))
                scope.cancel()
                delay(1000)
            }
        }
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.protocol.AMotionEvent
import ai.mobilerun.stream.protocol.ControlMessages
import android.content.Intent
import ai.mobilerun.stream.core.StreamStats
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.webrtc.EglRenderer
import org.webrtc.SurfaceViewRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
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

    /** Maximum tile mean absolute luma difference; local changes cannot cancel each other out. */
    private fun tileDifference(before: Bitmap, after: Bitmap, region: Rect): Double {
        var maximum = 0.0
        fun luma(pixel: Int) = .2126 * (pixel shr 16 and 255) +
            .7152 * (pixel shr 8 and 255) + .0722 * (pixel and 255)
        for (y in region.top until region.bottom step 4) for (x in region.left until region.right step 4) {
            var difference = 0.0
            var count = 0
            for (py in y until minOf(y + 4, region.bottom)) for (px in x until minOf(x + 4, region.right)) {
                difference += abs(luma(before.getPixel(px, py)) - luma(after.getPixel(px, py)))
                count++
            }
            maximum = max(maximum, difference / count)
        }
        return maximum
    }

    private suspend fun tapLatency(session: DeviceStreamSession, renderer: SurfaceViewRenderer): Pair<Double, Double> {
        session.sendControl(ControlMessages.keycode(0, 3))
        session.sendControl(ControlMessages.keycode(1, 3))
        delay(1500)
        val size = assertNotNull(session.videoSize.value)
        val x = requireNotNull(args.getString("tapX")).toDouble()
        val y = requireNotNull(args.getString("tapY")).toDouble()
        require(x in 0.0..size.displayWidth.toDouble() && y in 0.0..size.displayHeight.toDouble())
        var baseline = renderedFrame(renderer)
        val cx = (x / size.displayWidth * baseline.width).toInt()
        val cy = (y / size.displayHeight * baseline.height).toInt()
        // 64×64 upright-video pixels around the actual input point, scaled into the callback bitmap.
        val rx = (32.0 / size.displayWidth * baseline.width).toInt().coerceAtLeast(2)
        val ry = (32.0 / size.displayHeight * baseline.height).toInt().coerceAtLeast(2)
        val tapRegion = Rect((cx - rx).coerceAtLeast(0), (cy - ry).coerceAtLeast(0),
            (cx + rx + 1).coerceAtMost(baseline.width), (cy + ry + 1).coerceAtMost(baseline.height))
        // A separate central region measures app-launch transition, away from the dock icon.
        val launchRegion = Rect(baseline.width / 5, baseline.height / 5,
            baseline.width * 4 / 5, baseline.height * 3 / 5)
        try {
            withTimeout(10_000) {
                var stable = 0
                while (stable < 4) {
                    delay(100)
                    val next = renderedFrame(renderer)
                    stable = if (tileDifference(baseline, next, tapRegion) < 1.0) stable + 1 else 0
                    baseline.recycle()
                    baseline = next
                }
            }
            val tapChanged = CompletableDeferred<Long>()
            val launchChanged = CompletableDeferred<Long>()
            val startedNs = AtomicLong(Long.MAX_VALUE)
            val active = AtomicBoolean(true)
            lateinit var listener: EglRenderer.FrameListener
            listener = EglRenderer.FrameListener { bitmap ->
                if (bitmap != null) {
                    val frameNs = SystemClock.elapsedRealtimeNanos()
                    try {
                        if (active.get() && frameNs >= startedNs.get()) {
                            if (tileDifference(baseline, bitmap, tapRegion) >= 4.0) tapChanged.complete(frameNs)
                            if (tileDifference(baseline, bitmap, launchRegion) >= 4.0) launchChanged.complete(frameNs)
                        }
                    } finally { bitmap.recycle() }
                    if (active.get() && (!tapChanged.isCompleted || !launchChanged.isCompleted))
                        renderer.addFrameListener(listener, .25f)
                }
            }
            renderer.addFrameListener(listener, .25f)
            try {
                startedNs.set(SystemClock.elapsedRealtimeNanos())
                for (action in listOf(AMotionEvent.ACTION_DOWN, AMotionEvent.ACTION_UP)) {
                    assertTrue(session.sendControl(ControlMessages.touch(action, -1, size.displayWidth,
                        size.displayHeight, x, y, 1.0, 1, 1)), "control channel must be open")
                }
                return withTimeout(10_000) {
                    (tapChanged.await() - startedNs.get()) / 1_000_000.0 to
                        (launchChanged.await() - startedNs.get()) / 1_000_000.0
                }
            } finally {
                active.set(false)
                renderer.removeFrameListener(listener)
            }
        } finally { baseline.recycle() }
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
            val statsObserver = scope.launch { session.stats.collect {} }
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
                    val previous = steady.lastOrNull()
                    steady += withTimeout(10_000) {
                        session.stats.first { it != null && it !== previous && it.bitrateKbps != null }
                    }!!
                }
                val measurements = List(samples) { tapLatency(session, renderer) }
                val latency = measurements.map { it.first }.sorted()
                val appLaunch = measurements.map { it.second }.sorted()
                fun median(values: List<Double>): Double = if (values.size % 2 == 0)
                    (values[values.size / 2 - 1] + values[values.size / 2]) / 2 else values[values.size / 2]
                fun p90(values: List<Double>): Double = values[kotlin.math.ceil(values.size * .9).toInt() - 1]
                fun mean(metric: (StreamStats) -> Double?): Double? =
                    steady.mapNotNull(metric).takeIf { it.isNotEmpty() }?.average()
                val last = steady.last()
                val row = JSONObject().put("run", run + 1)
                    .put("firstFrameMs", last.timeToFirstFrameMs ?: JSONObject.NULL)
                    .put("fps", mean { it.fps } ?: JSONObject.NULL)
                    .put("bitrateKbps", mean { it.bitrateKbps } ?: JSONObject.NULL)
                    .put("rttMs", mean { it.rttMs } ?: JSONObject.NULL)
                    .put("candidateType", if (last.localCandidateType != null && last.remoteCandidateType != null)
                        "${last.localCandidateType}/${last.remoteCandidateType}" else JSONObject.NULL)
                    .put("latencyMedianMs", median(latency))
                    .put("latencyP90Ms", p90(latency))
                    .put("latencySamplesMs", JSONArray(latency))
                    .put("appLaunchMedianMs", median(appLaunch))
                    .put("appLaunchP90Ms", p90(appLaunch))
                    .put("appLaunchSamplesMs", JSONArray(appLaunch))
                    .put("steadySampleCount", steady.size)
                results.put(row)
                File(output, "benchmark.json").writeText(results.toString(2))
            } finally {
                activity?.let { host -> ui { host.stream.detach(); host.finish() } }
                session.close()
                statsObserver.cancel()
                scope.cancel()
            }
            // Teardown assertions follow the successful run so they cannot mask a measurement failure.
            withTimeout(10_000) {
                while (session.videoSize.value != null || session.stats.value != null) delay(50)
            }
            assertTrue(!session.sendControl(byteArrayOf(0)))
            delay(1000)
        }
    }
}

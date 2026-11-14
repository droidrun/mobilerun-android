// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentials
import ai.mobilerun.stream.core.StreamStatus
import ai.mobilerun.stream.core.VideoSize
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Runtime-only credentials and connection checks shared by both instrumented suites. */
internal class E2eDevice private constructor(val credentials: StreamCredentials) {
    private val rejected = AtomicInteger()
    private val real = OkHttpSignallingTransportFactory(OkHttpClient())
    val signalling = SignallingTransportFactory { creds, listener ->
        real.open(creds, object : SignallingListener by listener {
            override fun onFailure(httpStatus: Int?) {
                if (httpStatus == 401 || httpStatus == 404) rejected.set(httpStatus)
                listener.onFailure(httpStatus)
            }
        })
    }

    suspend fun awaitConnectedWithVideo(session: DeviceStreamSession): VideoSize = withTimeout(60.seconds) {
        while (session.status.value != StreamStatus.Connected || session.videoSize.value == null) {
            check(rejected.get() == 0) { "PENDING: device HTTP ${rejected.get()}" }
            delay(100)
        }
        requireNotNull(session.videoSize.value)
    }

    companion object {
        fun load(context: Context): E2eDevice {
            val file = File(context.filesDir, "stream-e2e.json")
            assumeTrue("no runtime credentials", file.exists())
            val json = JSONObject(file.readText())
            return E2eDevice(StreamCredentials(json.getString("streamUrl"), json.getString("streamToken")))
        }
    }
}

internal fun screenshotCrop(bytes: ByteArray, field: Boolean = false): IntArray {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    val left = if (field) bitmap.width / 6 else 0
    val top = (bitmap.height * .04).toInt()
    val width = if (field) bitmap.width * 2 / 3 else bitmap.width
    val height = if (field) (bitmap.height * .06).toInt() else (bitmap.height * .90).toInt()
    return IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, left, top, width, height)
        bitmap.recycle()
    }
}

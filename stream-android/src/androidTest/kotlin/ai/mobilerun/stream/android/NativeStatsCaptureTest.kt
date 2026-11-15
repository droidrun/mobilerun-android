// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Opt-in sanitized native stats capture; excluded from benchmark measurements. */
class NativeStatsCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Captures only parser inputs: identifiers are replaced, and addresses/certificates are omitted. */
    @Test fun recordNativeReports() = runBlocking {
        val output = File(context.filesDir, "e2e-out").also { it.mkdirs() }
        val device = E2eDevice.load(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var session: DeviceStreamSession
        lateinit var peer: WebRtcPeerTransport
        val factory = ai.mobilerun.stream.core.PeerTransportFactory { configuration, channels, listener ->
            WebRtcPeerTransportFactory({ WebRtcRuntime.factory(context) }, session.media,
                VideoCodecPreference.Auto).create(configuration, channels, listener).also {
                peer = it
            }
        }
        session = DeviceStreamSession(context, { device.credentials }, scope, StreamConfig(), device.signalling, factory)
        val fields = setOf("kind", "mediaType", "transportId", "codecId", "frameWidth", "frameHeight",
            "framesPerSecond", "bytesReceived", "framesDecoded", "jitterBufferDelay", "jitterBufferEmittedCount",
            "totalDecodeTime", "packetsLost", "framesDropped", "freezeCount", "selectedCandidatePairId",
            "localCandidateId", "remoteCandidateId", "currentRoundTripTime", "candidateType", "mimeType", "selected")
        val references = setOf("transportId", "codecId", "selectedCandidatePairId", "localCandidateId", "remoteCandidateId")
        val types = setOf("inbound-rtp", "transport", "candidate-pair", "local-candidate", "remote-candidate", "codec")
        val aliases = mutableMapOf<String, String>()
        fun alias(id: String): String = aliases.getOrPut(id) { "r${aliases.size}" }
        try {
            session.start()
            device.awaitConnectedWithVideo(session)
            delay(2000)
            val reports = JSONArray()
            repeat(2) {
                val report = withTimeout(10_000) { peer.statsReport() }
                val json = JSONObject()
                report.statsMap.values.filter { it.type in types }.forEach { record ->
                    val members = JSONObject()
                    record.members.filterKeys { it in fields }.forEach { (key, value) ->
                        members.put(key, if (key in references && value is String) alias(value) else value)
                    }
                    json.put(alias(record.id), JSONObject().put("type", record.type)
                        .put("timestampUs", record.timestampUs).put("members", members))
                }
                reports.put(json)
                delay(1000)
            }
            File(output, "rtc-stats-native.json").writeText(reports.toString(2))
            File(output, "native-frame.png").writeBytes(session.screenshot())
        } finally {
            session.close()
            scope.cancel()
        }
    }

}

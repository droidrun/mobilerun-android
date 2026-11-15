// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import kotlinx.serialization.json.*
import org.junit.Test
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamStatsParserTest {
    private val reports = readReports("/rtc-stats.json")

    private fun readReports(name: String): List<List<StatsRecord>> =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource(name)).readText())
        .jsonArray.map { report -> report.jsonObject.map { (id, element) ->
            val value = element.jsonObject
            StatsRecord(id, value.getValue("type").jsonPrimitive.content,
                value.getValue("timestampUs").jsonPrimitive.double,
                value.getValue("members").jsonObject.mapValues { (_, member) ->
                    val primitive = member.jsonPrimitive
                    if (primitive.isString) primitive.content else primitive.booleanOrNull ?: primitive.double
                })
        } }

    @Test fun recordedNativeReportsUseCounterIntervalsAndSelectedPair() {
        val reports = readReports("/rtc-stats-native.json")
        val parser = StreamStatsParser()
        assertNull(parser.parse(reports[0], 123.0).bitrateKbps)
        val sample = parser.parse(reports[1], 123.0)
        assertEquals(59.0, sample.fps)
        assertEquals(488, sample.width)
        assertEquals(1080, sample.height)
        assertEquals("video/H265", sample.codec)
        assertEquals(19.0, sample.rttMs)
        assertEquals("relay", sample.localCandidateType)
        assertEquals("prflx", sample.remoteCandidateType)
        assertEquals(52_704.0 / 1016.954, requireNotNull(sample.bitrateKbps), 1e-6)
        assertEquals(9.503 / 61, requireNotNull(sample.jitterBufferDelayMs), 1e-6)
        assertEquals(262.0 / 60, requireNotNull(sample.decodeTimeMs), 1e-6)
        assertEquals(0, sample.packetsLost)
        assertEquals(2, sample.framesDropped)
        assertEquals(0, sample.freezeCount)
    }

    @Test fun recordedReportsUseSelectedPairAndVideoCounterDeltas() {
        val parser = StreamStatsParser()
        val first = parser.parse(reports[0], null)
        assertNull(first.bitrateKbps)
        assertNull(first.decodeTimeMs)
        assertNull(first.jitterBufferDelayMs)
        assertNull(first.timeToFirstFrameMs)
        val sample = parser.parse(reports[1], 425.0)
        assertEquals(30.0, sample.fps)
        assertEquals(1080, sample.width)
        assertEquals(2400, sample.height)
        assertEquals("video/H264", sample.codec)
        assertEquals(1000.0, sample.bitrateKbps)
        assertEquals(24.0, sample.rttMs)
        assertEquals(15.0, sample.jitterBufferDelayMs)
        assertEquals(3.0, sample.decodeTimeMs)
        assertEquals(2, sample.packetsLost)
        assertEquals(3, sample.framesDropped)
        assertEquals(1, sample.freezeCount)
        assertEquals("relay", sample.localCandidateType)
        assertEquals("srflx", sample.remoteCandidateType)
        assertEquals(425.0, sample.timeToFirstFrameMs)
    }

    @Test fun absentMetricsRemainNullAndMissingVideoResetsBaseline() {
        val parser = StreamStatsParser()
        parser.parse(reports[0], null)
        val empty = parser.parse(emptyList(), null)
        assertNull(empty.fps)
        assertNull(empty.bitrateKbps)
        assertNull(empty.rttMs)
        assertNull(empty.localCandidateType)
        assertNull(empty.remoteCandidateType)
        assertNull(empty.freezeCount)
        assertNull(parser.parse(reports[1], null).bitrateKbps)
    }

    @Test fun resetsDuplicateTimesAndZeroFramesDoNotProduceInvalidRates() {
        val parser = StreamStatsParser()
        parser.parse(reports[1], null)
        val reset = parser.parse(reports[0], null)
        assertNull(reset.bitrateKbps)
        assertNull(reset.decodeTimeMs)
        val duplicate = parser.parse(reports[0], null)
        assertNull(duplicate.bitrateKbps)
        val unchanged = reports[0].map { StatsRecord(it.id, it.type, it.timestampUs + 1_000_000, it.members) }
        val zero = parser.parse(unchanged, null)
        assertEquals(0.0, zero.bitrateKbps)
        assertNull(zero.decodeTimeMs)
        assertNull(zero.jitterBufferDelayMs)
        val countersReset = parser.parse(reports[1].map {
            StatsRecord(it.id, it.type, it.timestampUs + 1_000_000, it.members + ("bytesReceived" to 0))
        }, null)
        assertNull(countersReset.bitrateKbps)
    }

    @Test fun counterDerivedFpsSupportsNativeBigIntegersAndStreamReplacement() {
        val parser = StreamStatsParser()
        val reports = reports.map { report -> report.map {
            StatsRecord(it.id, it.type, it.timestampUs,
                (it.members - "framesPerSecond") + if (it.type == "inbound-rtp" && it.id == "video")
                    mapOf("framesDecoded" to BigInteger(if (it.timestampUs == 1_000_000.0) "30" else "60"))
                else emptyMap())
        } }
        parser.parse(reports[0], null)
        assertEquals(30.0, parser.parse(reports[1], null).fps)
        val replacement = parser.parse(reports[1].map {
            StatsRecord(it.id + "new", it.type, it.timestampUs + 1_000_000, it.members)
        }, null)
        assertNull(replacement.bitrateKbps)
        assertNull(replacement.fps)
    }
}

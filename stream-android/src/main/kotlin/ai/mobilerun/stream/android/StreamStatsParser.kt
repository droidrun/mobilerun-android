// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

/** Primitive projection of RTCStats, also used by recorded-report tests. No addresses are retained. */
internal class StatsRecord(val id: String, val type: String, val timestampUs: Double, val members: Map<String, Any>) {
    fun number(key: String): Double? = (members[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    fun text(key: String): String? = members[key] as? String
}

/** One parser per connection attempt. All calls occur on the session's serial dispatcher. */
internal class StreamStatsParser {
    private var previous: StatsRecord? = null

    fun parse(records: Collection<StatsRecord>, firstFrameMs: Double?): StreamStats {
        val byId = records.associateBy { it.id }
        val video = records.firstOrNull {
            it.type == "inbound-rtp" && (it.text("kind") ?: it.text("mediaType")) == "video"
        }
        val old = previous?.takeIf { video != null && it.id == video.id && it.timestampUs < video.timestampUs }
        previous = video
        fun delta(key: String): Double? {
            val next = video?.number(key) ?: return null
            val before = old?.number(key) ?: return null
            return (next - before).takeIf { it >= 0 }
        }
        fun perFrame(total: String, count: String): Double? {
            val frames = delta(count)?.takeIf { it > 0 } ?: return null
            return delta(total)?.times(1000)?.div(frames)
        }
        val seconds = if (video != null && old != null) (video.timestampUs - old.timestampUs) / 1_000_000 else null
        val transport = video?.text("transportId")?.let(byId::get)
            ?: records.firstOrNull { it.type == "transport" && it.text("selectedCandidatePairId") != null }
        val pair = transport?.text("selectedCandidatePairId")?.let(byId::get)
            ?: records.firstOrNull { it.type == "candidate-pair" && it.members["selected"] == true }
        val candidateTypes = setOf("host", "srflx", "prflx", "relay")
        fun candidate(key: String): String? = pair?.text(key)?.let(byId::get)?.text("candidateType")
            ?.takeIf { it in candidateTypes }
        val local = candidate("localCandidateId")
        val remote = candidate("remoteCandidateId")
        return StreamStats(
            fps = video?.number("framesPerSecond") ?: seconds?.let { delta("framesDecoded")?.div(it) },
            width = video?.number("frameWidth")?.toInt(),
            height = video?.number("frameHeight")?.toInt(),
            codec = video?.text("codecId")?.let(byId::get)?.text("mimeType"),
            bitrateKbps = seconds?.let { delta("bytesReceived")?.times(8)?.div(it * 1000) },
            rttMs = pair?.number("currentRoundTripTime")?.times(1000),
            jitterBufferDelayMs = perFrame("jitterBufferDelay", "jitterBufferEmittedCount"),
            packetsLost = video?.number("packetsLost")?.toLong(),
            framesDropped = video?.number("framesDropped")?.toLong(),
            freezeCount = video?.number("freezeCount")?.toLong(),
            decodeTimeMs = perFrame("totalDecodeTime", "framesDecoded"),
            localCandidateType = local,
            remoteCandidateType = remote,
            candidateType = if (local != null && remote != null) "$local/$remote" else null,
            timeToFirstFrameMs = firstFrameMs,
        )
    }
}

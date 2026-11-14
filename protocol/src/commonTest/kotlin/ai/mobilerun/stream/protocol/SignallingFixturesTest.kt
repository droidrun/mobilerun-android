// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import ai.mobilerun.stream.conformance.fixtureObject
import ai.mobilerun.stream.conformance.runFixtureCases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Every example of `signalling/<message>.json` must parse and re-serialize to the same JSON object. */
class SignallingFixturesTest {
    private fun examples(file: String) = fixtureObject(file).getValue("examples").jsonArray

    private fun checkClient(file: String) = runFixtureCases(file, examples(file)) { _, case ->
        val json = case.getValue("json").jsonObject
        val message = SignallingJson.decodeFromJsonElement(ClientMessage.serializer(), json)
        assertEquals(json, Json.parseToJsonElement(message.toJson()))
    }

    private fun checkServer(
        file: String,
        phases: List<SignallingPhase> = listOf(SignallingPhase.Established),
    ) =
        runFixtureCases(file, examples(file)) { _, case ->
            val json = case.getValue("json").jsonObject
            for (phase in phases) {
                val frame = ServerMessage.parse(json.toString(), phase)
                assertIs<ServerFrame.Message>(frame, "phase $phase")
                assertEquals(json, Json.parseToJsonElement(frame.message.toJson()), "phase $phase")
            }
        }

    @Test
    fun clientRequestRtcConfiguration() = checkClient("signalling/client-request-rtc-configuration.json")

    @Test
    fun clientOffer() = checkClient("signalling/client-offer.json")

    @Test
    fun clientCandidate() = checkClient("signalling/client-candidate.json")

    @Test
    fun clientKeepAlive() = checkClient("signalling/client-keep-alive.json")

    @Test
    fun clientRequestFrame() = checkClient("signalling/client-request-frame.json")

    @Test
    fun clientOpenUrl() = checkClient("signalling/client-open-url.json")

    @Test
    fun clientScreenshotRequest() = checkClient("signalling/client-screenshot-request.json")

    @Test
    fun serverAnswer() = checkServer("signalling/server-answer.json")

    @Test
    fun serverCandidate() = checkServer("signalling/server-candidate.json")

    @Test
    fun serverRtcConfiguration() = checkServer(
        "signalling/server-rtc-configuration.json",
        listOf(SignallingPhase.AwaitingRtcConfiguration, SignallingPhase.Established),
    )

    @Test
    fun serverScreenshot() = checkServer("signalling/server-screenshot.json")

    @Test
    fun serverScreenshotError() = checkServer("signalling/server-screenshot-error.json")

    @Test
    fun clientCandidateWritesExplicitNulls() {
        val json = Json.parseToJsonElement(ClientMessage.Candidate("c", null, null, "s").toJson()).jsonObject
        assertEquals(setOf("type", "candidate", "sdpMid", "sdpMLineIndex", "sessionId"), json.keys)
        assertIs<JsonNull>(json.getValue("sdpMid"))
        assertIs<JsonNull>(json.getValue("sdpMLineIndex"))
    }

    @Test
    fun requestRtcConfigurationAlwaysOptsIntoLiveRefresh() {
        assertEquals(
            """{"type":"requestRtcConfiguration","sessionId":"s","supportsLiveRtcRefresh":true}""",
            ClientMessage.RequestRtcConfiguration("s").toJson(),
        )
    }

    @Test
    fun openUrlNoteExample() {
        val note = fixtureObject("signalling/client-open-url.json").getValue("notes").jsonArray.first()
            .jsonPrimitive.content
        val input = Regex("Input \"([^\"]*)\"").find(note)!!.groupValues[1]
        val expected = examples("signalling/client-open-url.json").first().jsonObject
            .getValue("json").jsonObject
        assertEquals(
            expected.getValue("url").jsonPrimitive.content,
            ClientMessage.OpenUrl.create(input, "sess_0123456789abcdef").url,
        )
    }

    @Test
    fun openUrlKeepsInputThatIsNotValidPercentEncoding() {
        assertEquals("100%", ClientMessage.OpenUrl.create("100%", "s").url)
        assertEquals("a%zzb", ClientMessage.OpenUrl.create("a%zzb", "s").url)
        assertEquals("%C3", ClientMessage.OpenUrl.create("%C3", "s").url)
        assertEquals("é+x", ClientMessage.OpenUrl.create("%C3%A9+x", "s").url)
    }

    @Test
    fun iceServerUrlsMayBeAStringOrAnArray() {
        val frame = ServerMessage.parse(
            """{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[""" +
                """{"urls":"stun:a"},{"urls":["turn:b"],"username":"u","credential":"c"}]}}""",
            SignallingPhase.Established,
        )
        val message = assertIs<ServerFrame.Message>(frame).message
        val servers = assertIs<ServerMessage.RtcConfiguration>(message).iceServers!!
        assertEquals(2, servers.size)
        assertEquals(listOf("stun:a"), servers[0].urls)
        assertNull(servers[0].username)
        assertNull(servers[0].credential)
        assertEquals(listOf("turn:b"), servers[1].urls)
        assertEquals("u", servers[1].username)
        assertEquals("c", servers[1].credential)
    }

    @Test
    fun sequenceTimingsMatchTheFixture() {
        val timing = fixtureObject("signalling/sequence.json").getValue("timing").jsonObject
        fun t(name: String) = timing.getValue(name).jsonPrimitive.long
        assertEquals(t("ws_open_timeout_ms"), SignallingTiming.WS_OPEN_TIMEOUT_MS)
        assertEquals(t("rtc_config_timeout_ms"), SignallingTiming.RTC_CONFIG_TIMEOUT_MS)
        assertEquals(t("request_frame_count"), SignallingTiming.REQUEST_FRAME_COUNT.toLong())
        assertEquals(t("request_frame_interval_ms"), SignallingTiming.REQUEST_FRAME_INTERVAL_MS)
        assertEquals(t("keep_alive_interval_ms"), SignallingTiming.KEEP_ALIVE_INTERVAL_MS)
        assertEquals(t("ice_restart_grace_ms"), SignallingTiming.ICE_RESTART_GRACE_MS)
        assertEquals(t("screenshot_timeout_ms"), SignallingTiming.SCREENSHOT_TIMEOUT_MS)
        assertEquals(7, timing.size, "every timing of the fixture is covered")
    }

    @Test
    fun dataChannelsMatchTheFixture() {
        val channels = fixtureObject("signalling/sequence.json").getValue("channels").jsonObject
        for ((key, spec) in mapOf("control" to DataChannels.control, "hover" to DataChannels.hover)) {
            val expected = channels.getValue(key).jsonObject
            assertEquals(expected.getValue("label").jsonPrimitive.content, spec.label)
            assertEquals(expected.getValue("id").jsonPrimitive.int, spec.id)
            assertEquals(expected.getValue("ordered").jsonPrimitive.content.toBoolean(), spec.ordered)
            assertEquals(expected.getValue("maxRetransmits").jsonPrimitive.intOrNull, spec.maxRetransmits)
        }
        assertEquals(2, channels.size)
    }
}

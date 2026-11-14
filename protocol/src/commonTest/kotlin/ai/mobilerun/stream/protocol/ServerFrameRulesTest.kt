// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import ai.mobilerun.stream.conformance.fixtureObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The per-phase handling rules of `signalling/sequence.json` (`ignored` and `failure_triggers`)
 * and the field rules in the `server-*` fixture notes.
 */
class ServerFrameRulesTest {
    private val awaiting = SignallingPhase.AwaitingRtcConfiguration
    private val established = SignallingPhase.Established

    private fun parse(text: String, phase: SignallingPhase) = ServerMessage.parse(text, phase)

    private fun message(text: String, phase: SignallingPhase): ServerMessage =
        assertIs<ServerFrame.Message>(parse(text, phase)).message

    private fun rtcConfiguration(text: String, phase: SignallingPhase): ServerMessage.RtcConfiguration =
        assertIs<ServerMessage.RtcConfiguration>(message(text, phase))

    private fun assertAll(expected: ServerFrame, phase: SignallingPhase, vararg texts: String) {
        for (text in texts) assertEquals(expected, parse(text, phase), text)
    }

    @Test
    fun theFixtureListsTheThreeIgnoreRules() {
        assertEquals(3, fixtureObject("signalling/sequence.json").getValue("ignored").jsonArray.size)
    }

    @Test
    fun awaitingConfigurationInvalidJsonFailsStartup() {
        assertAll(ServerFrame.StartupFailure, awaiting, "not json", "", "{\"type\":", "null")
    }

    @Test
    fun awaitingConfigurationIgnoresValidMessagesOfAnyOtherType() {
        assertAll(
            ServerFrame.Ignored,
            awaiting,
            """{"type":"answer","sdp":"x"}""",
            """{"type":"candidate","candidate":"c","sdpMid":"0","sdpMLineIndex":0}""",
            """{"type":"nope"}""",
            """{"no":"type"}""",
            """{"type":7}""",
            "[1,2]",
            "42",
        )
    }

    @Test
    fun awaitingConfigurationReplyThatCannotBeAConfigurationFailsStartup() {
        assertAll(
            ServerFrame.StartupFailure,
            awaiting,
            """{"type":"rtcConfiguration","rtcConfiguration":"x"}""",
        )
    }

    @Test
    fun configurationWithEmptyIceServersIsValidInBothPhases() {
        for (phase in listOf(awaiting, established)) {
            val text = """{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}"""
            assertEquals(emptyList(), rtcConfiguration(text, phase).iceServers, "$phase")
        }
    }

    @Test
    fun configurationWithoutIceServersHasNone() {
        for (phase in listOf(awaiting, established)) {
            for (text in listOf(
                """{"type":"rtcConfiguration"}""",
                """{"type":"rtcConfiguration","rtcConfiguration":null}""",
                """{"type":"rtcConfiguration","rtcConfiguration":{}}""",
            )) {
                assertNull(rtcConfiguration(text, phase).iceServers, "$phase $text")
            }
        }
        assertNull(rtcConfiguration("""{"type":"rtcConfiguration"}""", awaiting).configuration)
    }

    @Test
    fun initialReplyKeepsEveryConfigurationKey() {
        val text = """{"type":"rtcConfiguration","rtcConfiguration":""" +
            """{"iceServers":[],"iceTransportPolicy":"relay"}}"""
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(message(text, awaiting).toJson()))
    }

    @Test
    fun establishedDropsInvalidJsonAndUnknownTypes() {
        assertAll(
            ServerFrame.Ignored,
            established,
            "not json",
            "null",
            "[]",
            """{"type":"future"}""",
            """{"type":null}""",
            """{"type":[]}""",
            """{"type":{}}""",
            """{"no":"type"}""",
        )
    }

    @Test
    fun establishedIgnoresUnknownFieldsOnKnownTypes() {
        val text = """{"type":"answer","sdp":"x","extra":1}"""
        val answer = assertIs<ServerMessage.Answer>(message(text, established))
        assertEquals("x", answer.sdp)
    }

    @Test
    fun messageMissingARequiredStringFieldIsIgnored() {
        assertAll(
            ServerFrame.Ignored,
            established,
            """{"type":"answer"}""",
            """{"type":"screenshot","id":"a"}""",
            """{"type":"screenshot","dataUri":"d"}""",
            """{"type":"screenshot","id":1,"dataUri":"d"}""",
            """{"type":"screenshotError","id":"a"}""",
            """{"type":"screenshotError","message":"m"}""",
            """{"type":"screenshotError","id":"a","message":null}""",
        )
    }
}

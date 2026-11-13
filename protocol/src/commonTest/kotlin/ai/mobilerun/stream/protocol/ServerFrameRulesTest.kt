// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-phase handling rules of `signalling/sequence.json` (`ignored` and `failure_triggers`)
 * and the field rules in the `server-*` fixture notes.
 */
class ServerFrameRulesTest {
    private val awaiting = SignallingPhase.AwaitingRtcConfiguration
    private val established = SignallingPhase.Established

    private fun parse(text: String, phase: SignallingPhase) = ServerMessage.parse(text, phase)

    @Test
    fun theFixtureStatesTheRulesThisTestEncodes() {
        val ignored = fixtureObject("signalling/sequence.json").getValue("ignored").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, ignored.size)
        assertTrue(ignored[0].startsWith("Per connection phase."), ignored[0])
        assertTrue(ignored[1].startsWith("After the general message handler"), ignored[1])
    }

    @Test
    fun awaitingConfigurationInvalidJsonFailsStartup() {
        assertEquals(ServerFrame.StartupFailure, parse("not json", awaiting))
        assertEquals(ServerFrame.StartupFailure, parse("", awaiting))
        assertEquals(ServerFrame.StartupFailure, parse("{\"type\":", awaiting))
        assertEquals(ServerFrame.StartupFailure, parse("null", awaiting))
    }

    @Test
    fun awaitingConfigurationIgnoresValidMessagesOfAnyOtherType() {
        assertEquals(ServerFrame.Ignored, parse("""{"type":"answer","sdp":"x"}""", awaiting))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"candidate","candidate":"c","sdpMid":"0","sdpMLineIndex":0}""", awaiting))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"nope"}""", awaiting))
        assertEquals(ServerFrame.Ignored, parse("""{"no":"type"}""", awaiting))
        assertEquals(ServerFrame.Ignored, parse("[1,2]", awaiting))
        assertEquals(ServerFrame.Ignored, parse("42", awaiting))
        assertEquals(ServerFrame.Ignored, parse("""{"type":7}""", awaiting))
    }

    @Test
    fun awaitingConfigurationAcceptsTheInitialReply() {
        val frame = parse("""{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}""", awaiting)
        val message = assertIs<ServerMessage.RtcConfiguration>(assertIs<ServerFrame.Message>(frame).message)
        assertEquals(emptyList(), message.iceServers)
    }

    @Test
    fun awaitingConfigurationReplyWithoutConfigurationUsesDefaults() {
        val frame = parse("""{"type":"rtcConfiguration"}""", awaiting)
        val message = assertIs<ServerMessage.RtcConfiguration>(assertIs<ServerFrame.Message>(frame).message)
        assertNull(message.configuration)
        assertNull(message.iceServers)
    }

    @Test
    fun awaitingConfigurationReplyThatCannotBeAConfigurationFailsStartup() {
        assertEquals(ServerFrame.StartupFailure, parse("""{"type":"rtcConfiguration","rtcConfiguration":"x"}""", awaiting))
    }

    @Test
    fun establishedDropsInvalidJsonAndUnknownTypes() {
        assertEquals(ServerFrame.Ignored, parse("not json", established))
        assertEquals(ServerFrame.Ignored, parse("null", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"future"}""", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":null}""", established))
        assertEquals(ServerFrame.Ignored, parse("[]", established))
    }

    @Test
    fun establishedIgnoresUnknownFieldsOnKnownTypes() {
        val frame = parse("""{"type":"answer","sdp":"x","extra":1}""", established)
        assertEquals(ServerMessage.Answer("x"), assertIs<ServerFrame.Message>(frame).message)
    }

    @Test
    fun screenshotMissingAStringFieldIsIgnored() {
        assertEquals(ServerFrame.Ignored, parse("""{"type":"screenshot","id":"a"}""", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"screenshot","dataUri":"d"}""", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"screenshot","id":1,"dataUri":"d"}""", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"screenshotError","id":"a"}""", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"screenshotError","message":"m"}""", established))
        assertEquals(ServerFrame.Ignored, parse("""{"type":"screenshotError","id":"a","message":null}""", established))
    }

    @Test
    fun answerWithoutSdpIsIgnored() {
        assertEquals(ServerFrame.Ignored, parse("""{"type":"answer"}""", established))
    }

    @Test
    fun refreshWithoutIceServersHasNoIceServers() {
        val frame = parse("""{"type":"rtcConfiguration","rtcConfiguration":{}}""", established)
        assertNull(assertIs<ServerMessage.RtcConfiguration>(assertIs<ServerFrame.Message>(frame).message).iceServers)
        val nullFrame = parse("""{"type":"rtcConfiguration","rtcConfiguration":null}""", established)
        assertNull(assertIs<ServerMessage.RtcConfiguration>(assertIs<ServerFrame.Message>(nullFrame).message).iceServers)
    }

    @Test
    fun refreshWithEmptyIceServersIsAValidRefresh() {
        val frame = parse("""{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}""", established)
        val servers = assertIs<ServerMessage.RtcConfiguration>(assertIs<ServerFrame.Message>(frame).message).iceServers
        assertEquals(emptyList(), servers)
    }

    @Test
    fun initialReplyKeepsEveryConfigurationKey() {
        val text = """{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[],"iceTransportPolicy":"relay"}}"""
        val message = assertIs<ServerFrame.Message>(parse(text, awaiting)).message
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(text),
            kotlinx.serialization.json.Json.parseToJsonElement(message.toJson()),
        )
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val knownTypes = setOf("answer", "candidate", "rtcConfiguration", "screenshot", "screenshotError")

internal fun parseServerFrame(text: String, phase: SignallingPhase): ServerFrame {
    val element = try {
        SignallingJson.parseToJsonElement(text)
    } catch (_: SerializationException) {
        return invalidFrame(phase)
    }
    // The reference reads `message.type` right after JSON.parse, so a literal `null` throws there;
    // while awaiting the configuration that throw is caught and fails the attempt.
    if (element is JsonNull) return invalidFrame(phase)
    if (element !is JsonObject) return ServerFrame.Ignored

    val type = (element["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (type == null || type !in knownTypes) return ServerFrame.Ignored
    if (phase == SignallingPhase.AwaitingRtcConfiguration && type != "rtcConfiguration") return ServerFrame.Ignored

    return try {
        ServerFrame.Message(SignallingJson.decodeFromJsonElement(ServerMessage.serializer(), element))
    } catch (_: SerializationException) {
        // A message that misses a required field is dropped; an unusable initial configuration
        // cannot create the peer connection, which fails the attempt.
        invalidFrame(phase)
    }
}

private fun invalidFrame(phase: SignallingPhase): ServerFrame =
    if (phase == SignallingPhase.AwaitingRtcConfiguration) ServerFrame.StartupFailure else ServerFrame.Ignored

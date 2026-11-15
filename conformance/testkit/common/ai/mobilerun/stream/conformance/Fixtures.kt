// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Text of a file below `conformance/fixtures`, for example `control/touch.json`. */
internal expect fun readFixture(relativePath: String): String

internal fun fixtureJson(relativePath: String): JsonElement =
    Json.parseToJsonElement(readFixture(relativePath))

/** Lowercase hex, the notation of the control fixtures. */
internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** The case with the given `name` in a fixture's `cases` array. */
internal fun fixtureCase(relativePath: String, name: String): JsonObject =
    fixtureObject(relativePath).getValue("cases").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("name").jsonPrimitive.content == name }

internal fun fixtureObject(relativePath: String): JsonObject = fixtureJson(relativePath).jsonObject

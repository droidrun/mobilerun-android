// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.core.StreamCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Reuse the SDK value: its string representation redacts both URL and token.
fun parseCredentials(json: String): StreamCredentials {
    val fields = try { Json.parseToJsonElement(json).jsonObject } catch (_: Exception) {
        throw IllegalArgumentException("Credentials must be a JSON object")
    }
    fun field(name: String): String {
        val value = runCatching { fields[name]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull }.getOrNull()
        require(!value.isNullOrBlank()) { "Missing or blank $name" }
        return value
    }
    return StreamCredentials(field("streamUrl"), field("streamToken"))
}

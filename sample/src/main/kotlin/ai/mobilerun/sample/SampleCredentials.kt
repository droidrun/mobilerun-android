// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.core.StreamCredentials
import org.json.JSONObject

// Reuse the SDK value: its string representation redacts both URL and token.
fun parseCredentials(json: String): StreamCredentials {
    val fields = try { JSONObject(json) } catch (_: Exception) {
        throw IllegalArgumentException("Credentials must be a JSON object")
    }
    fun field(name: String): String {
        val value = fields.opt(name) as? String
        require(!value.isNullOrBlank()) { "Missing or blank $name" }
        return value
    }
    return StreamCredentials(field("streamUrl"), field("streamToken"))
}

/** Consumed launch input; helper mode carries no credentials in the app configuration. */
class SampleLaunchConfig(val credentials: StreamCredentials? = null, val backend: SampleBackend? = null)

fun parseLaunchConfig(json: String): SampleLaunchConfig {
    val fields = try { JSONObject(json) } catch (_: Exception) {
        throw IllegalArgumentException("Credentials must be a JSON object")
    }
    if (!fields.has("helperBaseUrl")) return SampleLaunchConfig(credentials = parseCredentials(json))
    val base = fields.opt("helperBaseUrl") as? String
    val device = fields.opt("deviceId") as? String
    require(!base.isNullOrBlank() && !device.isNullOrBlank()) { "Invalid helper configuration" }
    return SampleLaunchConfig(backend = SampleBackend(base, device))
}

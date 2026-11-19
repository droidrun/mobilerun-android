// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.core.StreamCredentials
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Immutable session source. This unauthenticated HTTP helper is for local development only. */
class SampleBackend(baseUrl: String, private val deviceId: String) {
    private val base = URI(baseUrl)

    init {
        val loopback = base.host == "localhost" || base.host == "127.0.0.1"
        val localHttp = base.scheme == "http" && loopback
        val validBase = base.scheme == "https" || localHttp
        val unadornedBase = base.host != null && base.userInfo == null &&
            base.query == null && base.fragment == null
        val validEndpoint = validBase && unadornedBase
        require(validEndpoint) { "Invalid helper URL" }
        require(Regex("[A-Za-z0-9_-]+").matches(deviceId)) { "Invalid device ID" }
    }

    suspend fun credentials(): StreamCredentials? {
        val metadata = request("", "GET")
        val streamUrl = metadata.opt("streamUrl") as? String
        if (streamUrl.isNullOrBlank()) return null
        return StreamCredentials(streamUrl, tokenProvider = { mintStreamKey() })
    }

    suspend fun mintStreamKey(): String = request("/stream-key", "POST").getString("key")

    private suspend fun request(suffix: String, method: String): JSONObject = withContext(Dispatchers.IO) {
        val url = base.toString().trimEnd('/') + "/api/v1/devices/$deviceId$suffix"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            val timeoutMs = 10_000
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-store")
            check(connection.responseCode in 200..299)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            throw IllegalStateException("Sample backend request failed")
        } finally {
            connection.disconnect()
        }
    }
}

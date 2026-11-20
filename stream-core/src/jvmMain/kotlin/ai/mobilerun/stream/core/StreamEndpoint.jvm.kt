// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import java.net.URI

internal actual fun isSecureProviderEndpoint(url: String): Boolean {
    val uri = try { URI(url) } catch (_: Exception) { return false }
    val host = uri.host?.lowercase() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val validPort = uri.port <= 65535
    if (!validPort) return false
    if (scheme == "wss") return true
    val ipv6Loopback = Regex("""\[(?:0*:)+0{0,3}1\]""").matches(host)
    val loopback = host == "localhost" || host == "127.0.0.1" || ipv6Loopback
    return scheme == "ws" && loopback
}

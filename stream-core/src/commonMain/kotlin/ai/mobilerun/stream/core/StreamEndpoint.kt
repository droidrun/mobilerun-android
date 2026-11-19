// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** Provider keys travel only to secure WebSockets, except exact local development loopbacks. */
internal expect fun isSecureProviderEndpoint(url: String): Boolean

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import java.io.File

internal actual fun readFixture(relativePath: String): String {
    val root = System.getProperty("conformance.fixtures")
        ?: error("system property conformance.fixtures is not set; run the tests through Gradle")
    return File(root, relativePath).readText()
}

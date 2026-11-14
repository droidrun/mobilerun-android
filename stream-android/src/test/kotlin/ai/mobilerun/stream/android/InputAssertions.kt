// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal fun List<ByteArray>.assertSent(vararg expected: ByteArray) {
    assertEquals(expected.size, size, "message count")
    expected.zip(this).forEachIndexed { index, (a, b) -> assertContentEquals(a, b, "message $index") }
}

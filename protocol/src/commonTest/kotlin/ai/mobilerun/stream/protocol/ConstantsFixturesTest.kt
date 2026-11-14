// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import ai.mobilerun.stream.conformance.fixtureObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The keyboard code map of `constants.json` must equal the Kotlin table. The constant objects are
 * generated from the same file and CI fails when the committed copy is stale.
 */
class ConstantsFixturesTest {
    @Test
    fun keyboardCodeMap() {
        val expected = fixtureObject("constants.json").getValue("code_map").jsonObject
            .mapValues { it.value.jsonPrimitive.int }
        val actual = KeyboardCodes.keycodeByCode
        println("conformance: constants.json/code_map exercised ${actual.size} of ${expected.size}")
        assertEquals(expected, actual)
    }
}

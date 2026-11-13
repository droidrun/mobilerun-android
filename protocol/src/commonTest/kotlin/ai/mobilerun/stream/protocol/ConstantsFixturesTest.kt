// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** `constants.json` must equal the Kotlin tables entry by entry. */
class ConstantsFixturesTest {
    private fun expected(section: String): Map<String, Int> =
        fixtureObject("constants.json").getValue(section).jsonObject.mapValues { it.value.jsonPrimitive.int }

    private fun check(section: String, actual: Map<String, Int>) {
        val exp = expected(section)
        println("conformance: constants.json/$section exercised ${actual.size} of ${exp.size}")
        assertEquals(exp, actual, "constants.json $section")
    }

    @Test
    fun controlMsgType() = check("control_msg_type", ControlMsgType.entries)

    @Test
    fun amotionEvent() = check("amotion_event", AMotionEvent.entries)

    @Test
    fun androidKeys() = check("android_keys", AndroidKeys.entries)

    @Test
    fun keyboardCodeMap() = check("code_map", KeyboardCodes.keycodeByCode)
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `reconnect/policy.json`: constants, the backoff table and the invariant. */
class ReconnectPolicyFixturesTest {
    private val policy = fixtureObject("reconnect/policy.json")

    @Test
    fun constantsMatchThePolicyFile() {
        fun long(name: String) = policy.getValue(name).jsonPrimitive.long
        assertEquals(policy.getValue("max_attempts").jsonPrimitive.int, ReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(long("ws_open_timeout_ms"), ReconnectPolicy.WS_OPEN_TIMEOUT_MS)
        assertEquals(long("rtc_config_timeout_ms"), ReconnectPolicy.RTC_CONFIG_TIMEOUT_MS)
        assertEquals(long("connect_timeout_ms"), ReconnectPolicy.CONNECT_TIMEOUT_MS)
        assertEquals(long("stability_ms"), ReconnectPolicy.STABILITY_MS)
        val backoff = policy.getValue("backoff").jsonObject
        assertEquals(backoff.getValue("base_delay_ms").jsonPrimitive.long, ReconnectPolicy.BASE_DELAY_MS)
        assertEquals(backoff.getValue("max_delay_ms").jsonPrimitive.long, ReconnectPolicy.MAX_DELAY_MS)
    }

    @Test
    fun delayCases() {
        val file = "reconnect/policy.json#delay_cases"
        runFixtureCases(file, policy.getValue("delay_cases").jsonArray) { _, case ->
            val attempt = case.getValue("attempt").jsonPrimitive.int
            val random = case.getValue("random").jsonPrimitive.double
            assertEquals(
                case.getValue("delay_ms").jsonPrimitive.long,
                ReconnectPolicy.delayMs(attempt, random),
                "attempt=$attempt random=$random",
            )
        }
    }

    @Test
    fun invariantsHold() {
        val invariants = policy.getValue("invariants").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("connect_timeout_ms > ws_open_timeout_ms + rtc_config_timeout_ms"), invariants)
        assertTrue(
            ReconnectPolicy.CONNECT_TIMEOUT_MS > ReconnectPolicy.WS_OPEN_TIMEOUT_MS + ReconnectPolicy.RTC_CONFIG_TIMEOUT_MS,
        )
    }
}

// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.conformance.fixtureObject
import ai.mobilerun.stream.conformance.runFixtureCases
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/** `reconnect/scenarios.json`, replayed on a fake clock exactly as the fixture README describes. */
class ReconnectScenariosFixturesTest {
    private val file = "reconnect/scenarios.json"

    private class Harness(random: Double) {
        val clock = FakeClock()
        var healCalls = 0
        var remountCalls = 0
        val controller = StreamReconnectController(
            onRemount = { remountCalls += 1 },
            onSnapshot = {},
            onHeal = { healCalls += 1 },
            scheduler = clock,
            random = { random },
        )

        fun apply(event: String) = when (event) {
            "attempt_mounted" -> controller.attemptMounted()
            "connected" -> controller.handleConnected()
            "disconnected" -> controller.handleDisconnected()
            "failure" -> controller.handleFailure()
            "wake" -> controller.handleWake()
            "retry" -> controller.retry()
            "disable" -> controller.disable()
            "dispose" -> controller.dispose()
            else -> error("unknown event $event")
        }
    }

    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

    @Test
    fun theEventVocabularyIsTheOneThisReplayUnderstands() {
        val events = fixtureObject(file).getValue("events").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf(
                "attempt_mounted", "connected", "disconnected", "failure",
                "wake", "retry", "disable", "dispose",
            ),
            events,
        )
    }

    @Test
    fun everyScenarioReplaysToTheExpectedStates() {
        val scenarios = fixtureObject(file).getValue("scenarios").jsonArray
        var expectations = 0
        runFixtureCases("$file#scenarios", scenarios) { _, scenario ->
            val harness = Harness(scenario.getValue("random").jsonPrimitive.double)
            val steps = scenario.getValue("steps").jsonArray.map { it.jsonObject }
            val expects = scenario.getValue("expect").jsonArray.map { it.jsonObject }
            val expectsAfterStep = expects.groupBy { it.int("after_steps") }
            var evaluated = 0
            for (i in 0..steps.size) {
                val due = expectsAfterStep[i].orEmpty().sortedBy { it.getValue("at_ms").jsonPrimitive.long }
                for (expect in due) {
                    harness.clock.advanceTo(expect.getValue("at_ms").jsonPrimitive.long)
                    check(expect, harness, "at ${expect.getValue("at_ms")} ms after $i steps")
                    evaluated += 1
                }
                if (i < steps.size) {
                    harness.clock.advanceTo(steps[i].getValue("at_ms").jsonPrimitive.long)
                    harness.apply(steps[i].getValue("event").jsonPrimitive.content)
                }
            }
            assertEquals(expects.size, evaluated, "every expectation is evaluated")
            expectations += evaluated
        }
        println("conformance: $file expectations evaluated $expectations")
    }

    private fun check(expect: JsonObject, harness: Harness, where: String) {
        val snapshot = harness.controller.snapshot
        val status = snapshot.status.name.lowercase()
        assertEquals(expect.getValue("status").jsonPrimitive.content, status, "status $where")
        assertEquals(expect.int("attempt"), snapshot.attempt, "attempt $where")
        assertEquals(expect.int("heal_calls"), harness.healCalls, "heal_calls $where")
        assertEquals(expect.int("remount_calls"), harness.remountCalls, "remount_calls $where")
        expect["defer_target_change"]?.let {
            val defer = harness.controller.shouldDeferTargetChange()
            assertEquals(it.jsonPrimitive.boolean, defer, "defer_target_change $where")
        }
        expect["pending_timers"]?.let {
            assertEquals(it.jsonPrimitive.int, harness.clock.pendingTimers, "pending_timers $where")
        }
    }
}

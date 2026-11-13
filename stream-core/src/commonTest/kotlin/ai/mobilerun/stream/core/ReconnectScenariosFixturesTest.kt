// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

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
            "disable" -> controller.setDisabled()
            "dispose" -> controller.dispose()
            else -> error("unknown event $event")
        }
    }

    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

    @Test
    fun theEventVocabularyIsTheOneThisReplayUnderstands() {
        val events = fixtureObject(file).getValue("events").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("attempt_mounted", "connected", "disconnected", "failure", "wake", "retry", "disable", "dispose"),
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
            var evaluated = 0
            var i = 0
            while (true) {
                for (expect in expects.filter { it.int("after_steps") == i }.sortedBy { it.getValue("at_ms").jsonPrimitive.long }) {
                    harness.clock.advanceTo(expect.getValue("at_ms").jsonPrimitive.long)
                    check(expect, harness, "at ${expect.getValue("at_ms")} ms after $i steps")
                    evaluated += 1
                }
                if (i >= steps.size) break
                harness.clock.advanceTo(steps[i].getValue("at_ms").jsonPrimitive.long)
                harness.apply(steps[i].getValue("event").jsonPrimitive.content)
                i += 1
            }
            assertEquals(expects.size, evaluated, "every expectation is evaluated")
            expectations += evaluated
        }
        println("conformance: $file expectations evaluated $expectations")
    }

    private fun check(expect: JsonObject, harness: Harness, where: String) {
        val snapshot = harness.controller.snapshot
        assertEquals(expect.getValue("status").jsonPrimitive.content, snapshot.status.name.lowercase(), "status $where")
        assertEquals(expect.int("attempt"), snapshot.attempt, "attempt $where")
        assertEquals(expect.int("heal_calls"), harness.healCalls, "heal_calls $where")
        assertEquals(expect.int("remount_calls"), harness.remountCalls, "remount_calls $where")
        expect["defer_target_change"]?.let {
            assertEquals(it.jsonPrimitive.boolean, harness.controller.shouldDeferTargetChange(), "defer_target_change $where")
        }
        expect["pending_timers"]?.let {
            assertEquals(it.jsonPrimitive.int, harness.clock.pendingTimers, "pending_timers $where")
        }
    }
}

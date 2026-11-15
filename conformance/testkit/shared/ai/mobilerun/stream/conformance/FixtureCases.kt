// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [check] on every element of [cases] and fails once at the end, listing every failing case by
 * its fixture name. Prints the coverage line `conformance: <file> exercised N of M`.
 */
internal fun runFixtureCases(
    file: String,
    cases: JsonArray,
    check: (name: String, case: JsonObject) -> Unit,
) {
    val failures = mutableListOf<String>()
    var exercised = 0
    for (element in cases) {
        val case = element.jsonObject
        val name = case["name"]?.let { it.jsonPrimitive.content } ?: "case #$exercised"
        exercised += 1
        try {
            check(name, case)
        } catch (e: Throwable) {
            failures += "[$file] \"$name\": ${e.message ?: e::class.simpleName}"
        }
    }
    println("conformance: $file exercised $exercised of ${cases.size}")
    assertEquals(cases.size, exercised, "every case of $file must be exercised")
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
}

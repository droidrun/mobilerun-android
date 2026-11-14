// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Text of a file below `conformance/fixtures`, for example `control/touch.json`. */
internal expect fun readFixture(relativePath: String): String

internal fun fixtureJson(relativePath: String): JsonElement =
    Json.parseToJsonElement(readFixture(relativePath))

/** Lowercase hex, the notation of the control fixtures. */
internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** The case with the given `name` in a fixture's `cases` array. */
internal fun fixtureCase(relativePath: String, name: String): JsonObject =
    fixtureObject(relativePath).getValue("cases").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("name").jsonPrimitive.content == name }

internal fun fixtureObject(relativePath: String): JsonObject = fixtureJson(relativePath).jsonObject

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

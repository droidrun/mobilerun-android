// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SampleCredentialsTest {
    @Test fun loadsTheE2eFormatAndRedactsLogging() {
        val credentials = parseCredentials("""{"streamUrl":"wss://example.test/stream", "streamToken":"private-token"}""")
        assertEquals("wss://example.test/stream", credentials.url)
        assertEquals("private-token", credentials.token)
        assertEquals("StreamCredentials(url=<redacted>, token=<redacted>)", credentials.toString())
    }

    @Test fun rejectsMissingUrl() {
        assertFailsWith<IllegalArgumentException> { parseCredentials("""{"streamToken":"token"}""") }
    }
    @Test fun rejectsMissingToken() {
        assertFailsWith<IllegalArgumentException> { parseCredentials("""{"streamUrl":"wss://example.test"}""") }
    }
    @Test fun rejectsBlankUrl() {
        assertFailsWith<IllegalArgumentException> { parseCredentials("""{"streamUrl":" ","streamToken":"token"}""") }
    }
    @Test fun rejectsBlankToken() {
        assertFailsWith<IllegalArgumentException> { parseCredentials("""{"streamUrl":"wss://example.test","streamToken":" "}""") }
    }
    @Test fun malformedInputDoesNotExposeItsContents() {
        val failure = assertFailsWith<IllegalArgumentException> { parseCredentials("private-token") }
        assertEquals("Credentials must be a JSON object", failure.message)
        assertEquals(null, failure.cause)
    }
}

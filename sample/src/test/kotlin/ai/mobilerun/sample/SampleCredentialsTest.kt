// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SampleCredentialsTest {
    @Test fun acceptsCredentialFreeHelperConfigAndRejectsInsecureRemoteHelper() {
        val config = parseLaunchConfig(
            """{"helperBaseUrl":"http://localhost:3001","deviceId":"device-a"}""",
        )
        assertEquals(null, config.credentials)
        kotlin.test.assertNotNull(config.backend)
        val invalid = listOf(
            """{"helperBaseUrl":"http://example.test:3001","deviceId":"device-a"}""",
            """{"helperBaseUrl":"http://localhost.evil:3001","deviceId":"device-a"}""",
            """{"helperBaseUrl":"http://localhost:3001?token=private","deviceId":"device-a"}""",
            """{"helperBaseUrl":"http://localhost:3001","deviceId":"../device-b"}""",
            """{"helperBaseUrl":"http://localhost:3001"}""",
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { parseLaunchConfig(it) } }
    }

    @Test fun loadsTheE2eFormatAndRedactsLogging() {
        val credentials = parseCredentials("""{"streamUrl":"wss://example.test/stream", "streamToken":"private-token"}""")
        assertEquals("wss://example.test/stream", credentials.url)
        assertEquals("private-token", credentials.token)
        assertEquals("StreamCredentials(url=<redacted>, token=<redacted>)", credentials.toString())
    }

    @Test fun rejectsInvalidFields() {
        val cases = listOf(
            """{"streamToken":"token"}""",
            """{"streamUrl":"wss://example.test"}""",
            """{"streamUrl":" ","streamToken":"token"}""",
            """{"streamUrl":"wss://example.test","streamToken":" "}""",
            """{"streamUrl":123,"streamToken":"token"}""",
            """{"streamUrl":"wss://example.test","streamToken":true}""",
            """{"streamUrl":null,"streamToken":"token"}""",
            """{"streamUrl":"wss://example.test","streamToken":{}}""",
        )
        cases.forEach { json ->
            assertFailsWith<IllegalArgumentException> { parseCredentials(json) }
        }
    }

    @Test fun malformedInputDoesNotExposeItsContents() {
        val failure = assertFailsWith<IllegalArgumentException> { parseCredentials("private-token") }
        assertEquals("Credentials must be a JSON object", failure.message)
        assertEquals(null, failure.cause)
    }
}

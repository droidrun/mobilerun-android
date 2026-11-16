// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.core.StreamCredentials
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SampleLaunchStateTest {
    private val application = RuntimeEnvironment.getApplication()
    private val file = File(application.filesDir, "stream-e2e.json")

    @Test fun launchReadsAndDeletesCredentialsFile() {
        file.writeText("""{"streamUrl":"wss://example.test/stream","streamToken":"private-token"}""")
        val state = SampleLaunchState(application)
        assertEquals("wss://example.test/stream", state.credentials?.url)
        assertEquals("private-token", state.credentials?.token)
        assertFalse(state.loadFailed)
        assertFalse(file.exists())
    }

    @Test fun invalidFileReportsFailureAndIsDeleted() {
        file.writeText("""{"streamToken":42}""")
        val state = SampleLaunchState(application)
        assertNull(state.credentials)
        assertTrue(state.loadFailed)
        assertFalse(file.exists())
        state.updateCredentials(StreamCredentials("wss://example.test/stream", "replacement"))
        assertFalse(state.loadFailed)
        assertEquals("replacement", state.credentials?.token)
    }
}

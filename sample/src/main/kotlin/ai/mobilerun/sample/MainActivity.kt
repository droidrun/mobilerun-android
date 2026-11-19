// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.core.StreamCredentials
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ViewModelProvider(this)[SampleLaunchState::class.java]
        setContentView(ComposeView(this).apply {
            setContent {
                SampleApp(state.credentials, state.loadFailed, state.backend) { state.updateCredentials(it) }
            }
        })
    }
}

// Retain loaded credentials through activity recreation, without saving them to instance state.
class SampleLaunchState(application: Application) : AndroidViewModel(application) {
    private val file = File(application.filesDir, "stream-e2e.json")
    private val loaded = runCatching { if (file.exists()) parseLaunchConfig(file.readText()) else null }
    var credentials = loaded.getOrNull()?.credentials
    var backend = loaded.getOrNull()?.backend
        private set
    var loadFailed = loaded.isFailure
        private set

    fun updateCredentials(value: StreamCredentials) {
        credentials = value
        backend = null
        loadFailed = false
    }
    init { file.delete() }
}

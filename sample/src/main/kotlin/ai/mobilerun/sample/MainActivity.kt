// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import android.app.Activity
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import java.io.File

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "stream-e2e.json")
        val loaded = runCatching { if (file.exists()) parseCredentials(file.readText()) else null }
        file.delete()
        setContentView(ComposeView(this).apply {
            setContent { SampleApp(loaded.getOrNull(), loaded.isFailure) }
        })
    }
}

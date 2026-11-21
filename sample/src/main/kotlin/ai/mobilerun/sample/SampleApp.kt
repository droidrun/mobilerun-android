// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.compose.DeviceStream
import ai.mobilerun.stream.compose.StreamLabels
import ai.mobilerun.stream.core.StreamError
import ai.mobilerun.stream.compose.StreamTheme
import ai.mobilerun.stream.compose.StreamThemeTokens
import ai.mobilerun.stream.compose.TaskApproval
import ai.mobilerun.stream.core.PendingApproval
import ai.mobilerun.stream.core.StreamCredentials
import ai.mobilerun.stream.core.StreamStats
import ai.mobilerun.stream.core.TaskApprovalController
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Instant
import java.util.Locale

@Composable
fun SampleApp(
    initial: StreamCredentials? = null,
    loadFailed: Boolean = false,
    backend: SampleBackend? = null,
    onCredentialsChange: (StreamCredentials) -> Unit = {},
) {
    SampleTheme { labels -> SampleContent(initial, loadFailed, backend, labels, onCredentialsChange) }
}

enum class SampleThemeMode(val label: String) { LIGHT("Light"), DARK("Dark"), BRAND("Brand") }

@Composable
fun SampleTheme(content: @Composable ColumnScope.(StreamLabels) -> Unit) {
    var theme by remember { mutableStateOf(SampleThemeMode.LIGHT) }
    val tokens = when (theme) {
        SampleThemeMode.DARK -> StreamThemeTokens.dark()
        SampleThemeMode.BRAND -> StreamThemeTokens.light().copy(primary = Color(0xFF2563EB), radius = 16.dp)
        SampleThemeMode.LIGHT -> StreamThemeTokens.light()
    }
    val labels = if (theme == SampleThemeMode.BRAND) StreamLabels(connecting = "Preparing", retry = "Try again",
        back = "Go back", home = "Start", recent = "Apps") else StreamLabels()
    StreamTheme(tokens) {
        Column(Modifier.fillMaxSize().background(tokens.background).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SampleThemeMode.entries.forEach { mode ->
                    SampleButton(mode.label, { theme = mode })
                }
            }
            content(labels)
        }
    }
}

@Composable
private fun ColumnScope.SampleContent(
    initial: StreamCredentials?,
    loadFailed: Boolean,
    backend: SampleBackend?,
    labels: StreamLabels,
    onCredentialsChange: (StreamCredentials) -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var target by remember { mutableStateOf(initial) }
    var helper by remember { mutableStateOf(backend) }
    var form by remember { mutableStateOf(initial == null && backend == null) }
    var error by remember { mutableStateOf(if (loadFailed) "Cannot load credentials; check the JSON file" else "") }
    var generation by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(false) }
    if (form) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Label("Stream URL")
            CredentialField(url, { url = it }, "Stream URL")
            Label("Stream token")
            CredentialField(token, { token = it }, "Stream token", secret = true)
            Label(error)
            SampleButton("Connect", {
                if (url.isBlank() || token.isBlank()) error = "Enter a stream URL and token"
                else {
                    val entered = StreamCredentials(url, token)
                    target = entered
                    helper = null
                    onCredentialsChange(entered)
                    generation++; form = false; error = ""
                }
            })
        }
    }
    val credentials = target
    val source = helper
    key(generation, credentials, source) {
        if (credentials != null || source != null) {
            val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            val session = remember {
                DeviceStreamSession(
                    context.applicationContext,
                    { source?.credentials() ?: credentials },
                    scope,
                )
            }
            DisposableEffect(session) {
                session.start()
                onDispose { session.close(); scope.cancel() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SampleButton("Credentials", { form = !form })
                SampleButton("Reconnect", { generation++ })
                SampleButton(if (showStats) "Hide stats" else "Stats", { showStats = !showStats })
            }
            // Reasons come from the SDK; an app shows its own text. The sample prints the reason name.
            var lastError by remember { mutableStateOf<StreamError?>(null) }
            LaunchedEffect(session) { session.errors.collect { lastError = it } }
            lastError?.let { Label("Last error: ${it.reason} (attempt ${it.attempt}${if (it.final) ", final" else ""})") }
            if (showStats) {
                val stats by session.stats.collectAsState()
                StatsPanel(stats)
            }
            DeviceStream(session, Modifier.weight(1f).fillMaxWidth(), labels = labels)
        } else Spacer(Modifier.weight(1f))
    }
    DemoApproval()
}

@Composable
private fun CredentialField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    val tokens = StreamTheme.tokens
    BasicTextField(value, onChange, Modifier.fillMaxWidth().border(1.dp, tokens.border).padding(12.dp)
        .semantics { contentDescription = label }, singleLine = true,
        textStyle = tokens.typography.body.copy(color = tokens.foreground),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable
private fun StatsPanel(stats: StreamStats?) {
    fun Double?.display(): String = this?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    Label("FPS ${stats?.fps.display()} · kbit/s ${stats?.bitrateKbps.display()} · RTT ${stats?.rttMs.display()} ms\n" +
        "Jitter buffer ${stats?.jitterBufferDelayMs.display()} ms · ICE ${stats?.localCandidateType ?: "—"}/${stats?.remoteCandidateType ?: "—"}\n" +
        "First frame ${stats?.timeToFirstFrameMs.display()} ms")
}

// Only this sample's local fake is used; no task endpoint is called.
@Composable
fun DemoApproval(now: () -> Long = System::currentTimeMillis) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingApproval?>(null) }
    var result by remember { mutableStateOf("") }
    var fetches by remember { mutableIntStateOf(0) }
    var approvalChanged by remember { mutableStateOf<(() -> Unit)?>(null) }
    val controller = remember {
        TaskApprovalController(scope,
            fetchApproval = { fetches++; pending },
            decide = { _, _, decision -> pending = null; result = "Demo: ${decision.value}" },
            subscribe = { _, changed ->
                approvalChanged = changed
                AutoCloseable { approvalChanged = null }
            })
    }
    DisposableEffect(controller) { controller.setTask("local-demo"); onDispose { controller.close() } }
    val state by controller.state.collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(state.approval) { if (state.approval == null) scroll.scrollTo(0) }
    Column(Modifier.fillMaxWidth().heightIn(max = 290.dp).verticalScroll(scroll).padding(8.dp)) {
        SampleButton("Demo approval", {
            result = ""
            pending = PendingApproval("demo", "payment_card", mapOf("label" to "Demo card", "targetPackage" to "example.shop"),
                Instant.fromEpochMilliseconds(now() + 30_000).toString())
            approvalChanged?.invoke()
        })
        if (result.isNotEmpty()) Label("$result · refetched ($fetches)")
        TaskApproval(state.approval, controller::decide, isPending = state.isDeciding, decision = state.decision)
    }
}

@Composable
fun SampleButton(text: String, onClick: () -> Unit) {
    val tokens = StreamTheme.tokens
    Box(Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(10.dp)) {
        BasicText(text, style = tokens.typography.body.copy(color = tokens.primary))
    }
}

@Composable
private fun Label(text: String) {
    BasicText(text, Modifier.padding(4.dp), style = StreamTheme.tokens.typography.body.copy(color = StreamTheme.tokens.foreground))
}

# mobilerun-android

Kotlin library for embedding Mobilerun device streaming and events.

## Status: pre-release

The headless core, Android connection layer and interactive video view exist. The optional default UI, approval controller and white-label theme exist; nothing is published.

| Module | Package | Contents |
|---|---|---|
| `:protocol` | `ai.mobilerun.stream.protocol` | protocol constants, binary control-message encoders, signalling message types and parser |
| `:stream-core` | `ai.mobilerun.stream.core` | session engine and transports, reconnect policy, input routing, approval controller and readiness polling |
| `:stream-android` | `ai.mobilerun.stream.android` | `DeviceStreamSession` and interactive `DeviceStreamView` |
| `:stream-compose` | `ai.mobilerun.stream.compose` | optional default UI, tokens, labels, navigation and approval card |

`:protocol` and `:stream-core` are Kotlin Multiplatform modules with a JVM target; all code lives in `commonMain`. `:stream-android` is an Android library (minSdk 24, compileSdk 36, JVM 17).

## Installation (after first release)

These coordinates are reserved for the first Maven Central release; they are not available yet.
With `mavenCentral()` configured, use:

```kotlin
dependencies {
    implementation("ai.mobilerun:mobilerun-stream-android:0.1.0")
    // Optional Compose UI; includes the Android streaming module transitively.
    implementation("ai.mobilerun:mobilerun-stream-compose:0.1.0")
}
```

Android requires minSdk 24 and JDK 17. For headless JVM use, choose
`ai.mobilerun:mobilerun-stream-core:0.1.0`; Gradle selects its JVM variant.
See [RELEASING.md](RELEASING.md) for artifact details and the release checklist.

## Streaming a device

```kotlin
val session = DeviceStreamSession(
    context = applicationContext,
    credentials = { StreamCredentials(url = streamUrl, token = streamToken) }, // null = not ready yet
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    config = StreamConfig(),            // audio muted, codec order VP9/H.265/H.264, H.264-only without an H.265 decoder
)
session.start()
session.status       // StateFlow<StreamStatus>: Connecting, Connected, Reconnecting(attempt), Unavailable
session.videoSize    // StateFlow<VideoSize?>, including rotation
session.stats        // StateFlow<StreamStats?>, one-second inbound video samples
session.close()                     // or cancel the scope: both release everything
```

The video track and EGL context stay internal; `DeviceStreamView` uses them to render without copies.

The credentials provider is called at start, once per reconnect cycle and when the server rejects the token (HTTP 401/403). Closing the signalling socket ends the session on the server, so a closed socket is treated like a failed peer connection: the whole connection is rebuilt with the reconnect policy's backoff. All sessions in a process share one libwebrtc factory, which lives until the process ends; `close()` releases a session's own peer connection, channels and tracks. Playout-delay hints are not available in the WebRTC Android API, so the jitter buffer keeps its adaptive default.

## Stream statistics and benchmark

`session.stats` is read-only and `null` while disconnected or after close. Collect it like the
status flow. Every connected second it reports inbound video fps, decoded dimensions, codec MIME
type, payload bitrate in kbit/s, selected-pair RTT in ms, jitter-buffer delay per emitted frame,
packet loss, dropped frames, freezes, decode time per frame, local/remote ICE candidate types, and
time from the connection attempt to its first decoded frame. Missing platform fields remain null.
Bitrate and per-frame durations need two reports; each new attempt resets the counter baseline.
RTT uses the transport's selected candidate pair, rather than an arbitrary nominated pair.

The instrumentation-only benchmark opens five connections and takes ten input-latency samples per
connection. Supply a running emulator (`ANDROID_SERIAL`) or `BENCHMARK_AVD`, plus runtime credentials
and an output directory outside the repository:

```sh
STREAM_E2E_CREDENTIALS="$RUNTIME_CREDENTIALS" STREAM_BENCHMARK_OUT="$BENCHMARK_OUTPUT" \
  TAP_X=200 TAP_Y=600 tool/benchmark
```

`TAP_X/Y` are upright video pixels selecting an existing harmless launcher app icon. Each sample
returns Home, waits for a stable region, taps that icon and records the first changed rendered
frame. Choose `BENCHMARK_REGION=left,top,width,height` in normalized picture coordinates to exclude
animated widgets and system bars (default `0.15,0.15,0.7,0.7`). A quarter-scale renderer callback
detects a mean-luma change of at least 4/255. This measures tap DOWN to rendered-frame callback;
it excludes physical display scanout. Failed stability/change checks fail the benchmark instead of
inventing a latency. `BENCHMARK_RUNS` and `BENCHMARK_SAMPLES` override the defaults. The output contains
raw samples in `benchmark.json` and a `benchmark-results.md` table (median and nearest-rank p90).
Credentials are copied only to the test app's private storage at run time and removed afterward.

The separate `StreamHardeningTest` uses the same runner and credentials. It exercises host visibility,
network loss/restoration, a ten-minute soak (PSS growth bounded by the larger of 32 MiB or 25% of the
warmed baseline), native-peer teardown and session collection. Run it with
`STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.StreamHardeningTest scripts/run-e2e.sh stream-android`.
It restores emulator networking even after failure. A rejected initial device login leaves live
measurement pending; it is not a successful benchmark or soak result.

## Video and input

```kotlin
val streamView = DeviceStreamView(context).apply {
    attach(session)
    hasControl = true
}
container.addView(streamView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
session.start()

streamView.showKeyboard()    // focuses the view and requests the local IME
streamView.hideKeyboard()
streamView.sendSystemKey(SystemKey.BACK) // also HOME and RECENT

// When this host is finished:
streamView.detach()          // does not close the session
session.close()
```

Use view methods on the main thread. Video fits within the view, preserving its aspect ratio and
rotation; letterbox areas and a view without video remain transparent. Removing the view from its
window releases its renderer; adding it again resumes rendering the attached session. Repeated
`attach` with the same session does nothing. The app owns the session's lifetime.

Compose uses the same view and input paths. Add `:stream-compose` and import
`ai.mobilerun.stream.compose.DeviceStream`; `:stream-android` has no Compose dependency:

```kotlin
DeviceStream(session = session, modifier = Modifier.fillMaxSize(), hasControl = true)
```

`hasControl = false` renders without sending input. It is a display-only switch, not a permission
check; the server decides access. Touch uses stable slots for up to ten fingers, coalesces movement
per frame and releases at the last position inside the video. Mouse drags use pointer -1; hover
uses only the hover channel. Wheel axes use Android's tick units, without the browser pixel scaling.
Hardware single-character keys inject text (including control characters, matching React); navigation, modifier and Ctrl/Command combinations inject
Android keycodes with React's Shift/Alt/Ctrl/Meta flags only; CapsLock XORs Shift for letters. Ctrl/Command+V pastes the local primary clipboard;
Ctrl/Command+M presses Menu. Single graphemes, including emoji and combining sequences, inject their produced text without normalization.
Keycode DOWNs track physical identity; only matched keyups send UP, using current modifiers. Text
and completed paste/menu shortcuts never produce orphan releases.
The IME uses a normal text keyboard with suggestions/autocorrect disabled and the no-personalized-learning hint on Android 8+. If an IME composes anyway, composition
stays local until `commitText` or `finishComposingText`; Backspace and editor actions send device keys. Committed CRLF (as one Enter), CR, LF and tab become ENTER/TAB; other
committed control characters are dropped. The local input buffer does not mirror the remote editor,
so remote selection, autocorrection and surrounding text are not exposed to the local IME.

The host's Back button or gesture is **never forwarded automatically**. Apps may opt in from their
own Back handler, for example with AndroidX Activity's `OnBackPressedDispatcher`:

```kotlin
onBackPressedDispatcher.addCallback(this) {
    streamView.sendSystemKey(SystemKey.BACK)
}
```

Decide in that handler when to navigate locally instead. `SystemKey.HOME` and `RECENT` are also
explicit actions. The Compose module supplies optional navigation chrome.

## Default UI and white-labeling

The optional `:stream-compose` module uses Compose foundation only. It does not read Material 3
colors or typography, show a logo, or put a product name in the UI. `DeviceStream` adds a bottom-left
status pill while connecting/reconnecting, an unavailable message with Retry, and a navigation bar
below the video. Retry calls the same session recovery path as a custom UI. Reconnecting labels take
precedence over `labels.connecting`; connected video remains visible with `hasControl = false`, which
also disables the navigation buttons. The standalone `StreamNavigationBar(onAction)` emits `SystemKey`
BACK/HOME/RECENT (4/3/187). `StreamStatusPill(label)` is reusable independently.

```kotlin
StreamTheme { // light by default; opt in with followSystem = true
    DeviceStream(
        session = session,
        modifier = Modifier.fillMaxSize(),
        labels = StreamLabels(connecting = "Preparing", retry = "Try again"),
        navigationBar = {}, // omit, or provide { state -> ... }
        // placeholder = { status, labels -> YourPlaceholder(status, labels) },
        // unavailable = { retry, labels -> YourUnavailable(retry, labels) },
    )
}
```

`StreamLabels` overrides every stream string, including accessible navigation names. `TaskApprovalLabels`
has the same twelve keys as React: `paymentCardTitle`, `waiting`, `card`, `app`, `domain`, `timeLeft`,
`expired`, `approve`, `deny`, `viewSwitch`, `details`, `device`. Defaults are English; `{time}` in
`timeLeft` and `{attempt}`/`{max}` in `StreamLabels.reconnectingAttempt` are replaceable placeholders.
Backend-provided fact values and error messages remain the integrator's responsibility to localize.

Override individual theme tokens via `copy`, without modifying the rest:

```kotlin
val tokens = StreamThemeTokens.dark().copy(
    primary = Color(0xFF2563EB),
    primaryForeground = Color.White,
    radius = 12.dp,
    typography = StreamTypography(body = TextStyle(fontSize = 16.sp)),
)
StreamTheme(tokens) { DeviceStream(session, Modifier.fillMaxSize()) }
```

All thirteen React semantic colors are exposed: background, foreground, card, cardForeground,
muted, mutedForeground, border, ring, primary, primaryForeground, accent, accentForeground,
destructive, plus radius and body/caption/title typography. The ring, accent and accentForeground
tokens are reserved for focus/accent styling in custom slots; defaults do not currently read them. Slots can read `StreamTheme.tokens`. Light is the default even when the system is dark; use
`StreamTheme(tokens = StreamThemeTokens.dark())` or `StreamTheme(followSystem = true)` to opt in.
The defaults are React's `styles.css` values converted once: OKLCH → OKLab → linear sRGB using
CSS Color 4 matrices, apply the sRGB transfer function, clip each channel to [0, 1], round to 8 bits.
Light neutrals are #FFFFFF/#0A0A0A/#F5F5F5/#737373/#E5E5E5/#A1A1A1/#171717/#FAFAFA;
destructive is #E7000B. Dark adds #262626 and destructive #FF6467; its border retains 10% white.
Native dp/sp replace CSS px; the default radius is 8 dp (6 dp for buttons) and text is 12/14/16 sp. Native buttons use
48 dp touch targets. Each property is independently replaceable.

The plain `DeviceStreamView` remains the View API for custom hosts. Navigation, pill, approval card,
and default overlays are Compose-only. A View host can embed them in `ComposeView` or build its own
chrome on the headless core.

## Task approvals

The app talks to **your backend**. Never ship an API key in an app. The backend returns a
`PendingApproval(approvalId, kind, details, expiresAt, deviceId)` and accepts an `ApprovalDecision`
(`decision.value` is the lowercase backend value, `approve` or `deny`).
Unknown kinds or invalid payment details show the generic heading and keep the actions available.

```kotlin
val controller = TaskApprovalController(
    scope = scope,
    fetchApproval = { taskId -> backend.pendingApproval(taskId) },
    sendDecision = { taskId, approvalId, decision -> backend.decide(taskId, approvalId, decision.value) },
    // subscribe = { taskId, changed -> backend.subscribe(taskId, changed) }, // returns AutoCloseable
)
controller.setTask(taskId)
// In Compose:
val state by controller.state.collectAsState()
TaskApproval(
    approval = state.approval,
    onDecide = controller::decide,
    isPending = state.isDeciding,
    decision = state.decision,
    error = state.error?.message,
    labels = TaskApprovalLabels(approve = "Allow", deny = "Reject"),
    // device = { DeviceStream(session, Modifier.fillMaxSize()) },
    // actions = { state -> YourApprovalActions(state.onDecide, state.isPending, state.decision) },
)
// On host disposal:
controller.close() // scope cancellation also cleans up
```

The controller loads immediately and polls every 3 seconds after the latest completed fetch. A
subscription turns polling off by default; set `pollIntervalMs` explicitly to combine them or to 0
to disable it. Push events only refetch. Overlapping/old-task results are dropped. `refetch()` does
not re-subscribe. Decisions are deduplicated, and every success/error triggers a refetch, including
404/409. A decision error survives successful fetches until the next decision or task change; a fetch
error clears after a successful fetch. `setTask(null)` clears state and stops work. Integrator timeouts/cancellation are errors, while
closing the controller cancels its work. Subscription, unsubscribe and error-handler failures cannot
crash the host.

`PendingApproval.paymentCardDetails()` validates only payment-card kinds.
`paymentCardDetails()` accepts a nonempty label (falling back to credentialName), targetPackage
and optional targetHost, ignoring wrongly typed optional fields. The card's isolated one-second
countdown stops ticking at expiry and is display only: **the backend
decides expiry and permission**. Expired approvals still forward decisions. The optional device slot
appears beside details on wide layouts; narrow layouts switch Details/Device while retaining the
slot's composition, including when resizing across the 768 dp breakpoint. The approval actions are independently replaceable.

## Visibility, network recovery and credentials

`DeviceStream` installs `StreamLifecycle` automatically. App background pauses keep-alive sends;
a real background-to-foreground or network-lost-to-available transition calls `session.handleWake()`, which retries pending backoff
immediately or starts a fresh recovery cycle from unavailable. It never starts a session that has
not been started, or revives a closed session. The host still owns start/close. For custom View UI,
create `StreamLifecycle(context, session)` on the main thread and close it on disposal. To manage
visibility yourself set `observeLifecycle = false`, then call `setHostVisible` and `handleWake`.
Registration's replayed ON_START and first network availability are ignored. Use one lifecycle adapter
per session.

The engine is the only credentials poll owner. Pass the integrator's backend callback directly:

```kotlin
val session = DeviceStreamSession(context, { backend.streamCredentials() }, scope)
session.start()
```

The callback returns `StreamCredentials?`; null means not ready. The engine polls every 3 seconds,
including after fetch failures, and refetches on recovery/heal. Each provider call is bounded by the reconnect connect timeout (45 seconds); a hung call cannot
block wake/retry work, and a timed-out initial call is polled again. Cancellation stops polling. No API
key belongs in the app: stream tokens are short-lived backend-issued values.

All overlay slots have a `BoxScope` receiver for placement. The navigation slot receives one
`StreamNavigationState` (canControl, labels, onAction); the approval action slot receives one
`ApprovalActionsState` (isPending, decision, labels, onDecide). Slots have non-null defaults, and an
empty navigation lambda removes the bar. The default empty device slot omits device layout. Public
value types are plain classes; theme and label types expose explicit `copy` methods.

## End-to-end check

`scripts/run-e2e.sh stream-android` runs an instrumented test against a live cloud device on an emulator: first frame, a tap sent as raw control bytes, a forced socket close with full rebuild, and a mid-session configuration refresh. Credentials come from a JSON file (`streamUrl`, `streamToken`) named by `STREAM_E2E_CREDENTIALS` and are copied into the test app's private storage at run time, never into the repository or the APK.

For the interactive view scenario, set `STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.DeviceStreamViewE2eTest`.
Supply `TAP_X`/`TAP_Y` for an app icon and `TEXT_X`/`TEXT_Y` for its text field, in upright
video pixels. Set `OPEN_URL` to a long page that supports scrolling and pinch zoom. Two scenarios
assert touch, swipe, wheel, two-finger zoom and landscape mapping, then IME commits, hardware keys,
clipboard paste, Backspace, Enter, BACK/HOME and display-only suppression. Comparisons use decoded
content or text-field crops that exclude the status bar. `STREAM_E2E_OUT` receives remote and
local-renderer screenshots and logs. Runtime target selection belongs outside the test.


The core behaves exactly like the [`@mobilerun/react`](https://github.com/droidrun/mobilerun-react) stream package. That is enforced with the shared conformance fixtures (`conformance/fixtures`, pinned by `conformance/VERSION`): every control message must encode to the exact fixture bytes, every signalling example must parse and re-serialize to the same JSON, and every reconnect scenario must replay to the expected states. All 199 input vectors from v0.2.0
replay through the same pure keyboard, IME, pointer, geometry and scroll logic used by Android.

`scripts/run-e2e.sh stream-compose` uses the same runtime-only credentials contract for the default
UI. The credential-free `DefaultUiGalleryTest` separately captures
connecting/reconnecting/unavailable/retry, light/dark/custom theme and labels, and
approval countdown/decision/refetch. Select it with
`STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.compose.DefaultUiGalleryTest`.
The separate `DefaultUiE2eTest` verifies live video with navigation and recovery using deliberately
expired credentials, then restores the provider and clicks Retry. A 401/404 for the initial valid
device is reported as pending for the live-device scenario. No real approval task is required.

## Tests

Requires JDK 17.

```sh
./gradlew check
```

The CI job runs unit tests and lint for all modules; the emulator test is not part of it. Each fixture test prints a line `conformance: <file> exercised N of M`; the two numbers must be equal.

## Conformance fixtures

`conformance/fixtures` is a vendored copy of the `fixtures/` directory of `mobilerun-sdk-conformance` at the tag in `conformance/VERSION`. To move to another tag, run the sync script with a local clone of that repository; it also regenerates the constants:

```sh
tool/sync-conformance.sh v0.2.0 /path/to/mobilerun-sdk-conformance
```

`protocol/src/commonMain/kotlin/ai/mobilerun/stream/protocol/Constants.kt` is generated from `conformance/fixtures/constants.json` by `tool/generate-constants.py` (Python 3, standard library only). CI fails if the committed file is out of date.

## License

Apache-2.0, see `LICENSE`.

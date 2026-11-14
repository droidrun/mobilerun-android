# mobilerun-android

Kotlin library for embedding Mobilerun device streaming and events.

## Status: pre-release

The headless core, Android connection layer and interactive video view exist. Default UI chrome is planned; nothing is published.

| Module | Package | Contents |
|---|---|---|
| `:protocol` | `ai.mobilerun.stream.protocol` | protocol constants, binary control-message encoders, signalling message types and parser |
| `:stream-core` | `ai.mobilerun.stream.core` | session engine and transports, reconnect policy, pure input geometry and routing |
| `:stream-android` | `ai.mobilerun.stream.android` | `DeviceStreamSession` and interactive `DeviceStreamView` |
| `:stream-compose` | `ai.mobilerun.stream.compose` | optional Compose `DeviceStream` wrapper |

`:protocol` and `:stream-core` are Kotlin Multiplatform modules with a JVM target; all code lives in `commonMain`. `:stream-android` is an Android library (minSdk 24, compileSdk 36, JVM 17).

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
session.close()                     // or cancel the scope: both release everything
```

The video track and EGL context stay internal; `DeviceStreamView` uses them to render without copies.

The credentials provider is called at start, once per reconnect cycle and when the server rejects the token (HTTP 401/403). Closing the signalling socket ends the session on the server, so a closed socket is treated like a failed peer connection: the whole connection is rebuilt with the reconnect policy's backoff. All sessions in a process share one libwebrtc factory, which lives until the process ends; `close()` releases a session's own peer connection, channels and tracks. Playout-delay hints are not available in the WebRTC Android API, so the jitter buffer keeps its adaptive default.

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
Hardware printable keys inject text; navigation, modifier and Ctrl/Command combinations inject
Android keycodes with React's Shift/Alt/Ctrl/Meta flags only; CapsLock XORs Shift for letters. Ctrl/Command+V pastes the local primary clipboard;
Ctrl/Command+M presses Menu. The IME requests immediate text input without suggestions. If an IME composes anyway, composition
stays local until `commitText` or `finishComposingText`; Backspace and editor actions send device keys. The local input buffer does not mirror the remote editor,
so remote selection, autocorrection and surrounding text are not exposed to the local IME.

The host's Back button or gesture is **never forwarded automatically**. Apps may opt in from their
own Back handler, for example with AndroidX Activity's `OnBackPressedDispatcher`:

```kotlin
onBackPressedDispatcher.addCallback(this) {
    streamView.sendSystemKey(SystemKey.BACK)
}
```

Decide in that handler when to navigate locally instead. `SystemKey.HOME` and `RECENT` are also
explicit actions; this module supplies no navigation bar or other UI chrome.

## End-to-end check

`stream-android/scripts/run-e2e.sh` runs an instrumented test against a live cloud device on an emulator: first frame, a tap sent as raw control bytes, a forced socket close with full rebuild, and a mid-session configuration refresh. Credentials come from a JSON file (`streamUrl`, `streamToken`) named by `STREAM_E2E_CREDENTIALS` and are copied into the test app's private storage at run time, never into the repository or the APK.

For the interactive view scenario, set `STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.DeviceStreamViewE2eTest`.
Supply `TAP_X`/`TAP_Y` for an app icon and `TEXT_X`/`TEXT_Y` for its text field, in upright
video pixels. Set `OPEN_URL` to a long page that supports scrolling and pinch zoom. Two scenarios
assert touch, swipe, wheel, two-finger zoom and landscape mapping, then IME commits, hardware keys,
clipboard paste, Backspace, Enter, BACK/HOME and display-only suppression. Comparisons use decoded
content or text-field crops that exclude the status bar. `STREAM_E2E_OUT` receives remote and
local-renderer screenshots and logs. Runtime target selection belongs outside the test.


The core behaves exactly like the [`@mobilerun/react`](https://github.com/droidrun/mobilerun-react) stream package. That is enforced with the shared conformance fixtures (`conformance/fixtures`, pinned by `conformance/VERSION`): every control message must encode to the exact fixture bytes, every signalling example must parse and re-serialize to the same JSON, and every reconnect scenario must replay to the expected states.

## Tests

Requires JDK 17.

```sh
./gradlew check
```

The CI job runs unit tests and lint for all modules; the emulator test is not part of it. Each fixture test prints a line `conformance: <file> exercised N of M`; the two numbers must be equal.

## Conformance fixtures

`conformance/fixtures` is a vendored copy of the `fixtures/` directory of `mobilerun-sdk-conformance` at the tag in `conformance/VERSION`. To move to another tag, run the sync script with a local clone of that repository; it also regenerates the constants:

```sh
tool/sync-conformance.sh v0.1.1 /path/to/mobilerun-sdk-conformance
```

`protocol/src/commonMain/kotlin/ai/mobilerun/stream/protocol/Constants.kt` is generated from `conformance/fixtures/constants.json` by `tool/generate-constants.py` (Python 3, standard library only). CI fails if the committed file is out of date.

## License

Apache-2.0, see `LICENSE`.

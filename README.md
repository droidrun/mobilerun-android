# mobilerun-android

Kotlin library for embedding Mobilerun device streaming and events.

## Status: pre-release

The headless core and the Android connection layer exist. There is no video view, touch or keyboard mapping or UI yet, and nothing is published.

| Module | Package | Contents |
|---|---|---|
| `:protocol` | `ai.mobilerun.stream.protocol` | protocol constants, binary control-message encoders, signalling message types and parser |
| `:stream-core` | `ai.mobilerun.stream.core` | reconnect policy and controller, the session engine (signalling and negotiation state machine) and the transport interfaces it drives |
| `:stream-android` | `ai.mobilerun.stream.android` | `DeviceStreamSession`: OkHttp signalling WebSocket and libwebrtc peer connection behind those interfaces |

`:protocol` and `:stream-core` are Kotlin Multiplatform modules with a JVM target; all code lives in `commonMain`. `:stream-android` is an Android library (minSdk 24, compileSdk 36, JVM 17).

## Streaming a device

```kotlin
val session = DeviceStreamSession(
    context = applicationContext,
    credentials = { StreamCredentials(url = streamUrl, token = streamToken) }, // null = not ready yet
    config = StreamConfig(),            // audio muted, codec order VP9/H.265/H.264, H.264-only without an H.265 decoder
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)
session.start()
session.status       // StateFlow<StreamStatus>: Connecting, Connected, Reconnecting(attempt), Unavailable
session.videoTrack   // StateFlow<VideoTrack?> for a renderer; changes on every reconnect
session.videoSize    // StateFlow<VideoSize?>, including rotation
session.sendControl(ControlMessages.touch(...))
session.close()
```

The credentials provider is called at start, once per reconnect cycle and when the server rejects the token (HTTP 401/403). Closing the signalling socket ends the session on the server, so a closed socket is treated like a failed peer connection: the whole connection is rebuilt with the reconnect policy's backoff. All sessions in a process share one libwebrtc factory, which lives until the process ends; `close()` releases a session's own peer connection, channels and tracks. Playout-delay hints are not available in the WebRTC Android API, so the jitter buffer keeps its adaptive default.

## End-to-end check

`stream-android/scripts/run-e2e.sh` runs an instrumented test against a live cloud device on an emulator: first frame, a tap sent as raw control bytes, a forced socket close with full rebuild, and a mid-session configuration refresh. Credentials come from a JSON file (`streamUrl`, `streamToken`) named by `STREAM_E2E_CREDENTIALS` and are copied into the test app's private storage at run time, never into the repository or the APK.

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

# Try the Android SDK

Requires JDK 17, Android SDK platform 36 and build-tools (Gradle downloads
its pinned toolchain dependencies), platform-tools, the Android emulator, and an
API 35 or newer AVD. Android Studio's SDK Manager and Device Manager can install
these. The SDK supports Android 7.0 (API 24) and newer; this guide uses API 35+.
Set `JAVA_HOME` and `ANDROID_HOME`; accept the SDK licenses with `sdkmanager --licenses`.
The Gradle wrapper pins Gradle 9.6.0; Kotlin is 2.4.21 and AGP is 9.5.0.

## Credentials and launch

Use an existing device in your Mobilerun account. Start the unauthenticated **local-only** helper:

```sh
cd sample/backend
npm ci
npm start
```

The helper reads `MOBILERUN_API_KEY` only from its process environment; set it using your secret
manager/environment, never in command arguments or launch JSON. `MOBILERUN_API_URL` optionally selects
an upstream base (with or without a trailing `/v1`); the helper uses exactly one `/v1`.
It binds **127.0.0.1:3001**. Never expose it through a tunnel. Production integrations must authenticate
callers and authorize device access in their own backend.
The helper only answers loopback Host headers on its listening port and requests without Origin.

- `GET /api/v1/devices/:deviceId` uses `sdk.devices.get(id)` and returns only `{id,state,streamUrl}`.
- `POST /api/v1/devices/:deviceId/stream-key` uses
  `sdk.streamTokens.create({deviceId,ttlSeconds:120},{maxRetries:0})` and returns only `{key,keyId,expiresAt}` (201/no-store).
  The upstream wire endpoint is `POST /v1/identity/stream-keys`.

Create a credential-free launch JSON file outside the checkout:

```json
{"helperBaseUrl":"http://localhost:3001","deviceId":"DEVICE_UUID"}
```

From the repo root:

```sh
scripts/run-sample.sh /path/to/launch.json
```

The runner builds the debug sample, boots its own AVD, sets `adb reverse tcp:3001 tcp:3001`,
installs the app and streams the JSON through stdin into app-private storage. The app consumes and
deletes it at launch. Cleanup removes the reverse mapping and stops only that owned emulator.
`SAMPLE_AVD` selects an idle AVD; `SAMPLE_EMULATOR_PORT` selects an unused even port (default 5582).
Never launch an AVD already in use. The debug sample permits cleartext only to localhost/127.0.0.1;
release and library manifests have no cleartext exception. No 10.0.2.2 configuration is needed.

The immutable helper/device configuration is bound to one session. Discovery resolves the target;
its `tokenProvider` POSTs once for every socket, including reconnect. Keys never enter app launch
JSON, UI, storage or logs. The provider deadline is 10 s, followed by the independent 45 s socket
watchdog (15 s open and 15 s configuration budgets). A failed/blank/timed-out provider has no fallback.
Stream URLs still require WSS outside exact loopback in provider mode.

The manual URL/token form and old `{"streamUrl":"wss://…","streamToken":"…"}` file format remain
**legacy diagnostics**. The static token path is deprecated; discovery is not. A pasted single-use
key cannot reconnect. Credentials stays available to select a manual target; Connect replaces the
helper session. Reconnect creates a fresh session using its existing immutable source.

## What to try

Use harmless apps and existing text fields on the cloud device. Each row includes
the expected result. Remote state changes persist on that device.

| Try | Expected result |
|---|---|
| Connect / first frame | The connecting overlay gives way to live video; aspect ratio is preserved. |
| Tap | The tapped remote control responds, including near the video's edges. |
| Swipe | A scrollable remote screen scrolls in the drag direction. |
| Two-finger gesture | A zoomable document zooms; Android Emulator supports pinch with Ctrl (Command on macOS) or its multitouch controls. |
| Type letters, emoji and CJK | Focus a remote field; use `showKeyboard()` to open the soft keyboard explicitly. Tapping the video only focuses it. Latin letters commit per character; CJK composition is finalized before sending. **Known limitation:** emoji and some CJK may not arrive; see below. Install a suitable IME if needed. |
| Back / Home / Recent | Each navigation button performs the corresponding remote Android action. |
| Rotate | Rotate the emulator using its toolbar; video remains fitted and touch mapping stays correct. Rotate the remote device too if its app supports it. |
| Background / foreground | Press the emulator's Home, then reopen Stream sample; the stream resumes or recovers. |
| Network off / on | Turn the emulator's Wi-Fi and mobile data off, then on; the session reconnects. Restore both after testing. |
| Forced reconnect | Tap Reconnect; a fresh connection produces video again. |
| Unavailable / Retry | Enter an invalid token and Connect, wait for recovery attempts to exhaust: unavailable and Retry appear. Restore valid credentials with Connect to recover. Retry with invalid credentials attempts recovery again. |
| Themes / labels | Light, Dark and Brand change the theme; Brand uses blue controls, rounded tokens and Go back / Start / Apps labels. |
| Demo approval | Tap Demo approval: a 30-second countdown appears. Approve or Deny dismisses the card and shows the decision plus refetch count. Start again to try the other action. No real task is sent. |
| Stats | Tap Stats: fps, payload kbit/s, RTT, jitter-buffer delay, ICE candidate types and first-frame time update. Missing values show —; Hide stats removes the panel. |

Typical values on an emulator over a relay connection: first frame ~3–4 s,
~40–55 fps and tap-to-pixel ~230 ms.

## Automated checks

```sh
./gradlew check --no-daemon
./gradlew :sample:assembleDebug --no-daemon
# Focused sample credentials and Compose checks:
./gradlew :sample:testDebugUnitTest
```

The sample tests
cover helper/static launch validation, consumed launch state, the native HTTP boundary,
theme tokens/labels and the local approval fake. `cd sample/backend && node --test server.test.mjs`
checks the helper over actual loopback HTTP with an injected SDK stub.
The library suites cover streaming and input; see [CONTRIBUTING.md](CONTRIBUTING.md#end-to-end-check) for instrumented checks.

## Known limitations

Publishing has not happened; use the source checkout. Static diagnostic mode reuses the entered token;
use helper/provider mode for single-use keys. Close 1001 immediately releases media and reconnects
in both modes; other-close release timing is unchanged. Emulator two-finger and IME support depend
on host configuration.

Some emoji and CJK characters may not arrive on Cloud Android devices through text injection.
The emoji/CJK checklist row records this limitation.

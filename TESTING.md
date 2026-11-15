# Try the Android SDK

Requires JDK 17, Python 3, Android SDK platform 36 and build-tools (Gradle downloads
its pinned toolchain dependencies), platform-tools, the Android emulator, and an
API 35 or newer AVD. Android Studio's SDK Manager and Device Manager can install
these. The SDK supports Android 7.0 (API 24) and newer; this guide uses API 35+.
Set `JAVA_HOME` and `ANDROID_HOME`; accept the SDK licenses with `sdkmanager --licenses`.
The Gradle wrapper pins Gradle 9.6.0; Kotlin is 2.4.21 and AGP is 9.5.0.

## Credentials and launch

Use an existing dev cloud device. Your integrator backend obtains its short-lived
`streamUrl` and `streamToken` and supplies them to the app. For internal testing,
a dev API key on a test account can call `GET /devices/{id}` through the dev API;
copy the stream fields from that response. Never ship an API key in an app.
Create a local JSON file outside the checkout (do not commit it):

```json
{"streamUrl":"wss://your-stream-endpoint", "streamToken":"your-short-lived-token"}
```

```sh
scripts/run-sample.sh /path/to/credentials.json
```

The script builds the debug sample, boots the first configured AVD, installs it,
and streams the JSON into the app's private storage through `adb run-as`. The app
consumes and deletes that file at launch. `SAMPLE_AVD` selects a specific AVD;
`SAMPLE_EMULATOR_PORT` selects an unused even port (default 5582). If installed,
`~/.local/bin/mr-heavy` holds the host's build/emulator lock for the entire run.
Keep the script running while testing; Ctrl-C stops its emulator and releases the
lock. It never uses an already running emulator. Close other instances of the
selected AVD before launch. On a fresh machine, create an AVD before running.

You can also launch the installed sample manually and paste both fields into its
Connect form. The token is obscured; credentials stay in memory after loading,
are never saved in instance state or logs, and are lost when the process exits.
Use Credentials to edit them; Connect applies changes. Reconnect starts a fresh
session with the current values. For expired tokens, load fresh credentials.

## What to try

Use harmless apps and existing text fields on the cloud device. Each row includes
the expected result. Remote state changes persist on that device.

| Try | Expected result |
|---|---|
| Connect / first frame | The connecting overlay gives way to live video; aspect ratio is preserved. |
| Tap | The tapped remote control responds, including near the video's edges. |
| Swipe | A scrollable remote page scrolls in the drag direction. |
| Two-finger gesture | A zoomable page zooms; Android Emulator supports pinch with Ctrl (Command on macOS) or its multitouch controls. |
| Type emoji and CJK | Focus a remote field, use its local IME, and enter `Hello 👋 中文`; committed text appears remotely. Install a suitable IME if needed. |
| Back / Home / Recent | Each navigation button performs the corresponding remote Android action. |
| Rotate | Rotate the emulator using its toolbar; video remains fitted and touch mapping stays correct. Rotate the remote device too if its app supports it. |
| Background / foreground | Press the emulator's Home, then reopen Stream sample; the stream resumes or recovers. |
| Network off / on | Turn the emulator's Wi-Fi and mobile data off, then on; the session reconnects. Restore both after testing. |
| Forced reconnect | Tap Reconnect; a fresh connection produces video again. |
| Unavailable / Retry | Enter an invalid token and Connect, wait for recovery attempts to exhaust: unavailable and Retry appear. Restore valid credentials with Connect to recover. Retry with invalid credentials attempts recovery again. |
| Themes / labels | Light, Dark and Brand change the theme; Brand uses blue controls, rounded tokens and Go back / Start / Apps labels. |
| Demo approval | Tap Demo approval: a 30-second countdown appears. Approve or Deny dismisses the card and shows the decision plus refetch count. Start again to try the other action. No real task is sent. |
| Stats | Tap Stats: fps, payload kbit/s, RTT, jitter-buffer delay, ICE candidate types and first-frame time update. Missing values show —; Hide stats removes the panel. |

Reference measurements from five connections: first frame 1.5–4.1 s, 38–56 fps,
RTT 21–25 ms and static-screen video payload about 42 kbit/s. Pooled app-launch
stimulus tap-region median/p90: 232/251 ms; separate central app-transition
median/p90: 233/259 ms. These exclude display scanout and are reference values,
not pass thresholds. Static content bitrate does not measure motion throughput.

## Automated checks

```sh
./gradlew check :sample:assembleDebug
# Focused sample credentials and Compose checks:
./gradlew :sample:testDebugUnitTest
```

When a host has `mr-heavy`, wrap Gradle checks with it as well. The sample tests
cover JSON validation/redaction, theme tokens/labels and the local approval fake.
The library suites cover streaming and input; see README for instrumented checks.

## Known limitations

Events subscriptions await a backend token contract. Publishing has not happened;
use the source checkout. The sample credentials provider reuses the entered token;
a production backend should refresh it. Emulator two-finger and IME support depend
on host configuration. Purchase flows are not part of this checklist.

# Contributing

Use JDK 17, Android SDK 36 and the pinned Gradle wrapper. Kotlin is 2.4.21.
Build and try the example app with [TESTING.md](TESTING.md); it covers setup,
launch configuration, the helper and the manual feature checklist.

Use commit messages in the form `type(scope): message`. Open pull requests against `main`.
Report security problems privately as described in [SECURITY.md](SECURITY.md).
For artifact coordinates and the release checklist, see [RELEASING.md](RELEASING.md).

## End-to-end check

`scripts/run-e2e.sh stream-android` runs an instrumented test against a live cloud device on an emulator: first frame, a tap sent as raw control bytes, a forced socket close with full rebuild, and a mid-session configuration refresh. Credentials come from a JSON file (`streamUrl`, `streamToken`) named by `STREAM_E2E_CREDENTIALS` and are copied into the test app's private storage at run time, never into the repository or the APK.

For the interactive view scenario, set `STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.DeviceStreamViewE2eTest`.
Supply `TAP_X`/`TAP_Y` for an app icon and `TEXT_X`/`TEXT_Y` for its text field, in upright
video pixels. Set `OPEN_URL` to a long document that supports scrolling and pinch zoom. Two scenarios
assert touch, swipe, wheel, two-finger zoom and landscape mapping, then IME commits, hardware keys,
clipboard paste, Backspace, Enter, BACK/HOME and display-only suppression. Comparisons use decoded
content or text-field crops that exclude the status bar. `STREAM_E2E_OUT` receives remote and
local-renderer screenshots and logs. Runtime target selection belongs outside the test.


Input routing follows the shared conformance vectors v0.2.0. The protocol and recovery contract is enforced with the shared conformance fixtures (`conformance/fixtures`, pinned by `conformance/VERSION`): every control message must encode to the exact fixture bytes, every signalling example must parse and re-serialize to the same JSON, and every reconnect scenario must replay to the expected states. All 199 input vectors from v0.2.0
replay through the shared keyboard, IME, pointer, geometry and scroll logic. All 21 shared IME vectors also replay unmodified through the Android input connection.

`scripts/run-e2e.sh stream-compose` uses the same runtime-only credentials contract for the default
UI. The credential-free `DefaultUiGalleryTest` separately captures
connecting/reconnecting/unavailable/retry, light/dark/custom theme and labels, and
approval countdown/decision/refetch. Select it with
`STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.compose.DefaultUiGalleryTest`.
The separate `DefaultUiE2eTest` verifies live video with navigation and recovery using deliberately
expired credentials, then restores the provider and clicks Retry. A 401/404 for the initial valid
device is reported as `Device unavailable:` for the live-device scenario. No real approval task is required.


## Tests

Requires JDK 17.

```sh
./gradlew check
```

The CI job runs unit tests and lint for all modules; the emulator test is not part of it. Each fixture test prints a line `conformance: <file> exercised N of M`; the two numbers must be equal.


## Conformance fixtures

Maintainers refresh the vendored `conformance/fixtures` from the shared fixture source at the tag
in `conformance/VERSION`. The sync script also regenerates constants:

```sh
tool/sync-conformance.sh v0.2.0 /path/to/fixture-source
```

`protocol/src/commonMain/kotlin/ai/mobilerun/stream/protocol/Constants.kt` is generated from `conformance/fixtures/constants.json` by `tool/generate-constants.py` (Python 3, standard library only). CI fails if the committed file is out of date.



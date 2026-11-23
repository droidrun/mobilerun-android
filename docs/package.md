← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Package

The SDK includes a headless core, Android connection layer, interactive video view, optional default UI,
approval controller and white-label theme. Version `v0.1.0` uses a git tag and JitPack distribution.

| Module | Package | Contents |
|---|---|---|
| `:protocol` | `ai.mobilerun.stream.protocol` | protocol constants, binary control-message encoders, signalling message types and parser |
| `:stream-core` | `ai.mobilerun.stream.core` | session engine and transports, reconnect policy, input routing, approval controller and readiness polling |
| `:stream-android` | `ai.mobilerun.stream.android` | `DeviceStreamSession` and interactive `DeviceStreamView` |
| `:stream-compose` | `ai.mobilerun.stream.compose` | optional default UI, tokens, labels, navigation and approval card |

Protocol types, raw channel sends and reconnect implementation types require `InternalStreamApi`
opt-in and may change in any release; they are excluded from the supported API. Only
`ReconnectPolicy.MAX_ATTEMPTS` is public reconnect configuration. `StreamConfig` contains `muted`
(default `true`) and `videoCodec` (`Auto` or `H264Only`). Stream operations throw a `StreamException`
subclass: `StreamNotConnectedException`, `StreamDisconnectedException`, `ScreenshotFailedException`
or `ScreenshotTimeoutException`.

`:protocol` and `:stream-core` are Kotlin Multiplatform modules with a JVM target; shared code lives
in `commonMain`, with JVM URL validation in `jvmMain`. `:stream-android` is an Android library
(minSdk 24, compileSdk 36, JVM 17).


## Installation

Configure the filtered JitPack repository as shown in the [README](../README.md#install).
Once the public `v0.1.0` tag has built successfully on JitPack, use one of:

```kotlin
dependencies {
    implementation("com.github.droidrun.mobilerun-android:mobilerun-stream-android:v0.1.0")
    // Optional Compose UI; includes the Android streaming module transitively.
    // implementation("com.github.droidrun.mobilerun-android:mobilerun-stream-compose:v0.1.0")
}
```

Android requires minSdk 24 and JDK 17. For headless JVM use, choose
`com.github.droidrun.mobilerun-android:mobilerun-stream-core:v0.1.0`; Gradle selects its JVM variant.
See [RELEASING.md](../RELEASING.md) for artifact details and the release checklist.


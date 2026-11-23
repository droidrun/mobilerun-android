# Third-party notices

Mobilerun's SDK source is licensed under [Apache-2.0](LICENSE). Its runtime dependencies have their
own licenses. Gradle resolves these dependencies separately; their code is not vendored in this
repository, but app packaging can include it in the distributed APK or app bundle.

| Runtime dependency | License | Upstream |
|---|---|---|
| WebRTC Android (`io.github.webrtc-sdk:android:150.7871.04`) | BSD-3-Clause; bundled third-party components have additional notices | [Android distribution](https://github.com/webrtc-sdk/android), [WebRTC license](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE), [WebRTC third-party components](https://webrtc.googlesource.com/src/+/refs/heads/main/third_party/) |
| Kotlin standard library | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| kotlinx.coroutines and kotlinx.serialization | Apache-2.0 | [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt), [Serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |
| AndroidX (including Compose, Lifecycle and their transitive AndroidX libraries) | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| OkHttp and its transitive Okio dependency | Apache-2.0 | [OkHttp](https://github.com/square/okhttp/blob/master/LICENSE.txt), [Okio](https://github.com/square/okio/blob/master/LICENSE.txt) |
| JetBrains annotations (where included transitively) | Apache-2.0 | [Java annotations](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) |

This inventory summarizes dependency families; it is not a substitute for their full license texts
or a complete inventory of code inside WebRTC's native binaries. The Gradle wrapper is build tooling,
licensed under Apache-2.0, and is not an app runtime dependency.

App distributors must inspect the exact resolved dependency versions and native archives, retain
applicable copyright notices, license texts and NOTICE files, and include required third-party
acknowledgments with their app. In particular, review the WebRTC distribution's upstream third-party
notices rather than treating all bundled native code as covered solely by BSD-3-Clause. Recheck these
obligations when updating dependencies; shrinking or repackaging does not itself remove them.

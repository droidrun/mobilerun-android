# mobilerun-android

Kotlin library for embedding Mobilerun device streaming and events.

## Status: pre-release core

Only the headless, platform-independent core exists so far. There is no WebRTC, WebSocket, video, input or UI code yet, and nothing is published.

| Module | Package | Contents |
|---|---|---|
| `:protocol` | `ai.mobilerun.stream.protocol` | protocol constants, binary control-message encoders, signalling message types and parser |
| `:stream-core` | `ai.mobilerun.stream.core` | reconnect policy and the reconnect controller (injectable clock and jitter, no real timers) |

Both are Kotlin Multiplatform modules with a JVM target; all code lives in `commonMain`.

The core behaves exactly like the [`@mobilerun/react`](https://github.com/droidrun/mobilerun-react) stream package. That is enforced with the shared conformance fixtures (`conformance/fixtures`, pinned by `conformance/VERSION`): every control message must encode to the exact fixture bytes, every signalling example must parse and re-serialize to the same JSON, and every reconnect scenario must replay to the expected states.

## Tests

Requires JDK 17.

```sh
./gradlew check
```

Each fixture test prints a line `conformance: <file> exercised N of M`; the two numbers must be equal.

## Conformance fixtures

`conformance/fixtures` is a vendored copy of the `fixtures/` directory of `mobilerun-sdk-conformance` at the tag in `conformance/VERSION`. To move to another tag, run the sync script with a local clone of that repository; it also regenerates the constants:

```sh
tool/sync-conformance.sh v0.1.0 /path/to/mobilerun-sdk-conformance
```

`protocol/src/commonMain/kotlin/ai/mobilerun/stream/protocol/Constants.kt` is generated from `conformance/fixtures/constants.json` by `tool/generate-constants.py` (Python 3, standard library only). CI fails if the committed file is out of date.

## License

Apache-2.0, see `LICENSE`.

← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Quick start (from source)

Clone this repository and open it in Android Studio with JDK 17 and Android SDK 36.
Inside this checkout, add `implementation(project(":stream-compose"))` to an app module.
External apps can add the published artifact after the first release; until then, use this source checkout.
Obtain a stream URL from your integrator backend and mint a fresh single-use key per socket;
never put an API key in the app.
Create and close a session with your screen's lifetime, then embed the default UI:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val session = DeviceStreamSession(context, credentials = { backend.streamCredentials() }, scope = scope)
session.start()
// Inside your composable:
StreamTheme { DeviceStream(session, Modifier.fillMaxSize()) }
// When the screen is disposed:
session.close()
scope.cancel()
```

`backend.streamCredentials()` discovers a target or returns null while not ready:

```kotlin
StreamCredentials(url = streamUrl, tokenProvider = { backend.mintStreamKey(deviceId) })
```

`StreamTokenProvider` is `suspend () -> String`. The callback belongs to that target and is called
once immediately before **each** new signalling socket, including automatic retries, failed upgrades,
wake and manual retries. Discovery is separate and retains its cycle-level polling semantics.
A callback-only update does not restart healthy media; the next attempt uses the latest callback.
A changed device should get a new session.

The provider has an independent **10,000 ms** deadline. A provider exception (including its own
cancellation), empty/blank output or timeout counts one attempt failure without opening a socket. There is no static/URL token fallback.
Late results after stop, cancellation or target replacement open nothing. Nonblank keys retain their
exact bytes. Provider mode requires `wss` and a parsed host, except `ws` to exact localhost,
127.0.0.1 or IPv6 loopback. The **45 s** connect watchdog and **15 s** WebSocket-open / subsequent
**15 s** RTC-configuration budgets start at socket allocation, after key acquisition. ICE-server
refresh on an existing socket never calls the provider.

Your backend calls `sdk.api.post('/identity/stream-keys', {body: {deviceId, ttlSeconds: 120},
maxRetries: 0})` with `@mobilerun/sdk@6.2.1` and a base URL ending in `/v1`: the wire endpoint is
`POST /v1/identity/stream-keys`. Return only `{key,keyId,expiresAt}` with 201 and `Cache-Control: no-store`.
Keys are consumed at admission, even if the downstream upgrade fails; never cache or retry a mint
request automatically. API keys stay on your authenticated, device-authorizing backend.

## Errors

`session.errors` is a `SharedFlow<StreamError>` with one event per failed connection attempt. It has no
replay: collect it before `start()`. A `StreamError` has `reason`, a log-safe `message` (never a key or URL),
`attempt` (failures in the current cycle, from 1) and `final`, which is `true` when retries stopped and
`retry()` or `handleWake()` must start a new cycle. Show your own text to users.

| `StreamErrorReason` | Meaning |
|---|---|
| `CredentialsUnavailable` | The token provider threw, returned a blank key or exceeded 10 s. |
| `TokenRejected` | The upgrade answered HTTP 401: the key was rejected, already used or expired. |
| `ConnectionRefused` | The socket ended before it opened for another reason (HTTP 403 or other status, wrong device, refused origin, no route). |
| `Disconnected` | An open session dropped, including the server's session cap. |
| `DeviceUnavailable` | Reserved; the signalling protocol has no offline message yet. |
| `Unknown` | Anything else (for example a malformed startup message). |

```kotlin
scope.launch { session.errors.collect { error -> if (error.final) showBanner(error.reason) } }
```

## Legacy static token

The static `token` field/path is deprecated: a key is single-use, so it cannot reconnect, and the
session logs one `MobilerunStream` warning per session when it is used. It is retained for legacy diagnostics:
`StreamCredentials(streamUrl, staticToken)` still works, including empty-token URL handling.
A pasted single-use key cannot support reconnect. Credentials discovery itself is not deprecated.
For a runnable app, one-command emulator launcher, and feature checklist, see [TESTING.md](../TESTING.md).
The sample helper only answers loopback Host headers on its listening port and requests without Origin.


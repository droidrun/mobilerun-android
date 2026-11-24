---
name: mobilerun-device-stream
description: Use when integrating a live Mobilerun device stream into a partner Android app with the Kotlin SDK, backend-issued tokens, error handling and white-label Compose UI.
---

# Mobilerun device stream for Android

## When to use

Use for embedding live device video and remote control in an Android app. This
checkout is pre-release: add `implementation(project(":stream-compose"))` to an
app module inside the checkout; use `:stream-android` for a custom View host.
Requires Android 7.0+ (API 24), JDK 17 and Android SDK 36 to build. Do not assume
the planned Maven coordinates are published; check [package docs](../../docs/package.md).

## Backend: mint one token per connection

Authenticate the app user and authorize access to the requested device on your
backend before minting. Keep `MOBILERUN_API_KEY` in the server environment only.
Use `@mobilerun/sdk@6.3.0` in a Node server handler after authorization:

```js
import { MobilerunSDK } from '@mobilerun/sdk';

// Node server only. Authenticate the user and authorize this device before minting.
const client = new MobilerunSDK({
  apiKey: process.env.MOBILERUN_API_KEY,
  maxRetries: 0,
});
const allowedOrigins = ['https://app.example.com']; // optional browser origin policy
const { key, keyId, expiresAt } = await client.streamTokens.create({
  deviceId,
  ttlSeconds: 120,
  allowedOrigins,
});
res.writeHead(201, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
res.end(JSON.stringify({ key, keyId, expiresAt }));
```

The response is `{key, keyId, expiresAt}`. Tokens start with `dr_st_`, are single-use
and have a default TTL of 120 seconds. `allowedOrigins` is optional and binds browser
use to the listed sites; it does not replace native user/device authorization.
Revoke an unused token on the server with `await client.streamTokens.delete(keyId)`.

REST fallback: `POST /v1/identity/stream-keys` with bearer API-key authentication on the server.

Do not cache tokens or automatically retry mint requests. A token is consumed
at admission even if the downstream WebSocket upgrade fails. The app's backend
client must extract `key` as a `String`, rather than return the whole JSON object.

## Client: provider and minimal embed

`StreamCredentials.tokenProvider` is a `StreamTokenProvider` (`suspend () -> String`),
called immediately before **every** signalling connection, including reconnects,
wake and manual retries. Fetch a new key from your backend each time; never cache
or reuse it. Use `wss://api.mobilerun.ai/v1/devices/{deviceId}/stream`; the SDK adds
the token query parameter. Provider mode requires secure WebSockets except exact
loopback development hosts.

```kotlin
import ai.mobilerun.stream.android.DeviceStreamSession
import ai.mobilerun.stream.compose.DeviceStream
import ai.mobilerun.stream.compose.StreamTheme
import ai.mobilerun.stream.core.StreamCredentials
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// Host-owned setup, once per screen/device; not on each recomposition.
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val session = DeviceStreamSession(
    context = context,
    credentials = {
        StreamCredentials(
            url = "wss://api.mobilerun.ai/v1/devices/$deviceId/stream",
            tokenProvider = { backend.mintStreamKey(deviceId) },
        )
    },
    scope = scope,
)
session.start()
// Inside your composable:
StreamTheme { DeviceStream(session, Modifier.fillMaxSize()) }
// When the screen is disposed:
session.close()
scope.cancel()
```

`backend.mintStreamKey` is your app's authenticated backend client, not an SDK
method. Credentials discovery (the outer callback) is separate: it may return
`null` while the target is not ready. It is not the per-socket token provider.
Use a new session when changing device. The host owns start/close;
`DeviceStream` observes lifecycle automatically but does not start the session.
A closed session cannot restart.

## Errors and recovery

Collect `session.errors` (`SharedFlow<StreamError>`) **before** `session.start()`;
it has no replay. Each event contains `reason`, log-safe `message`, `attempt`
(from 1 in the current cycle) and `final`. Show your own localized user message.
`final == true` means automatic retries stopped: the default Retry UI remains;
custom UI can call `session.retry()` after resolving the cause.

| `StreamErrorReason` | Meaning and action |
|---|---|
| `CredentialsUnavailable` | Provider threw, returned blank or exceeded 10 seconds. Check backend authentication, authorization and availability; let SDK backoff retry with a fresh mint. |
| `TokenRejected` | Upgrade returned HTTP 401. Check used, expired or revoked tokens and mint a fresh one for the authorized device. |
| `ConnectionRefused` | Socket closed before opening for another reason, including HTTP 403, wrong device, refused origin or no route. Check target, origin policy and connectivity. |
| `Disconnected` | An open session dropped, including the server's 30-minute cap. Let the SDK reconnect with a fresh token. |
| `DeviceUnavailable` | Reserved: the current signalling protocol has no offline message. If supported later, check device readiness before retrying. |
| `Unknown` | Other attempt failure, such as malformed startup data. Inspect safe diagnostics and retry after correcting the cause. |

Kotlin reports errors through the flow, not an `onError` parameter. Provider
failure never falls back to a static or URL token. See [stream keys](../../docs/stream-keys.md).

## Labels and theming

Pass `labels = StreamLabels(...)` to `DeviceStream`. All nine stream keys have
English defaults: `connecting`, `reconnecting`, `reconnectingAttempt`, `unavailable`,
`retry`, `back`, `home`, `recent`, `disconnected`. Preserve `{attempt}` and `{max}`
in `reconnectingAttempt`. `disconnected` appears on the first reconnect after an
open session drops. Navigation labels include accessibility names.

Use `StreamThemeTokens.light()` or `.dark()` and `.copy(...)` to override
`background`, `foreground`, `card`, `cardForeground`, `muted`, `mutedForeground`,
`border`, `ring`, `primary`, `primaryForeground`, `accent`, `accentForeground`,
`destructive`, `radius` and `typography`. `StreamTypography` supplies `body`,
`caption` and `title`. `ring`, `accent` and `accentForeground` are reserved for
custom slots. Keep Retry text readable against its background.

```kotlin
import ai.mobilerun.stream.compose.StreamLabels
import ai.mobilerun.stream.compose.StreamThemeTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val tokens = StreamThemeTokens.dark().copy(
    primary = Color(0xFF2563EB),
    primaryForeground = Color.White,
    radius = 12.dp,
)
StreamTheme(tokens = tokens) {
    DeviceStream(
        session = session,
        labels = StreamLabels(connecting = "Preparing", retry = "Try again"),
        navigationBar = {}, // optional: remove the default navigation bar
    )
}
```

The theme is scoped to its Compose subtree and independent of Material. Light
is the default; opt into system appearance with `StreamTheme(followSystem = true)`.
Replacement `placeholder` and `unavailable` slots receive `StreamOverlayState`
(status, labels, retry); `navigationBar` receives `StreamNavigationState`
(canControl, labels, onAction). Custom slots can read `StreamTheme.tokens`.
For a View host use `DeviceStreamView`; default chrome is Compose-only.
See [default UI](../../docs/default-ui.md) for slot and approval-label details.

## Security rules

- Never ship an API key in an APK, client configuration or client request to Mobilerun.
- An unused stream token grants view and control of one device: it can be redeemed
  once before expiry. First admission consumes it; expiry/consumption does not
  imply immediate termination of an already admitted session.
- Keep tokens out of logs, analytics, persistent storage and shared caches; use
  HTTPS for the app-to-backend request and authorize every mint.
- `StreamCredentials(url, token)` is legacy and deprecated, only for existing
  integrations until retirement. It reuses its static token on reconnect and
  therefore cannot reconnect with single-use keys. `tokenProvider` takes
  precedence when both are supplied; there is no static fallback.

## Troubleshooting

- **401 at connect:** check used, expired or revoked keys and the configured
  origin policy (wrong origins can instead yield 403/`ConnectionRefused`). Mint
  again through the provider; do not reuse the rejected key or append a second token.
- **422 on mint:** read the response's `issues` on the backend and correct
  `deviceId`, `ttlSeconds` or `allowedOrigins`; do not retry an unchanged payload.
- **Provider failures:** confirm the callback returns only a nonblank `key`,
  within 10 seconds. Check backend sign-in and device authorization; avoid an
  extra retry loop around the SDK's bounded retry policy.

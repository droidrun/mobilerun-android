← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Streaming a device

```kotlin
val session = DeviceStreamSession(
    context = applicationContext,
    credentials = {
        StreamCredentials(url = streamUrl, tokenProvider = { backend.mintStreamKey(deviceId) })
    }, // return null while not ready
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    config = StreamConfig(),            // audio muted, codec order VP9/H.265/H.264, H.264-only without an H.265 decoder
)
session.start()
session.status       // StateFlow<StreamStatus>: Connecting, Connected, Reconnecting(attempt), Unavailable
session.videoSize    // StateFlow<VideoSize?>, including rotation
session.stats        // StateFlow<StreamStats?>, one-second inbound video samples
session.close()                     // or cancel the scope: both release everything
```

After `close()` or scope cancellation, status stops updating and controls are disabled. A closed
session cannot restart.

The video track and EGL context stay internal; `DeviceStreamView` uses them to render without copies.

Close **1001** immediately releases peer, channels, media, timers and pending screenshots, then
reconnects with normal bounded backoff in both provider and static mode. Static mode reuses its token.
Other closes keep the existing deferred release at remount.

The credentials-discovery provider is called at start, once per reconnect cycle and when the server rejects the token (HTTP 401/403). Closing the signalling socket ends the session on the server, so a closed socket is treated like a failed peer connection: the whole connection is rebuilt with the reconnect policy's backoff. All sessions in a process share one libwebrtc factory, which lives until the process ends; `close()` releases a session's own peer connection, channels and tracks. Playout-delay hints are not available in the WebRTC Android API, so the jitter buffer keeps its adaptive default.


## Visibility, network recovery and credentials

`DeviceStream` installs `StreamLifecycle` automatically. App background pauses keep-alive sends;
a real background-to-foreground or network-lost-to-available transition calls `session.handleWake()`, which retries pending backoff
immediately or starts a fresh recovery cycle from unavailable. It never starts a session that has
not been started, or revives a closed session. The host still owns start/close. For custom View UI,
create `StreamLifecycle(context, session)` on the main thread and close it on disposal. To manage
visibility yourself set `observeLifecycle = false`, then call `setVisible` and `handleWake`.
Registration's replayed ON_START and first network availability are ignored. Use one lifecycle adapter
per session.

The engine is the only credentials poll owner. Pass the integrator's backend callback directly:

```kotlin
val session = DeviceStreamSession(context, { backend.streamCredentials() }, scope)
session.start()
```

The callback returns `StreamCredentials?`; null means not ready. The engine polls every 3 seconds,
including after fetch failures, and refetches on recovery/heal. Each provider call is bounded by the reconnect connect timeout (45 seconds); a hung suspending call cannot
block wake/retry work, and a timed-out initial call is polled again. Cancellation stops polling. No API
key belongs in the app: stream tokens are short-lived backend-issued values.
A token-only change while connected is stored for the next connection. A URL change, or a change
while negotiating, remounts immediately; changes during backoff wait for the scheduled attempt.
An empty token preserves the URL query, and credential string descriptions redact both fields.

All overlay slots have a `BoxScope` receiver for placement and receive one
`StreamOverlayState` (status, labels, retry). The navigation slot receives one
`StreamNavigationState` (canControl, labels, onAction); the approval action slot receives one
`ApprovalActionsState` (isPending, decision, labels, onDecide). Slots have non-null defaults, and an
empty navigation lambda removes the bar. The default empty device slot omits device layout. Public
value types are plain classes; theme and label types expose explicit `copy` methods.


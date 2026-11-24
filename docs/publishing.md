# Microphone and camera publishing

Publishing sends this app's microphone, optionally with camera, into the remote device's virtual
inputs on the existing viewer connection. Audio is required. Viewing and downstream mute remain
independent; the SDK never captures on connect or resumes publishing after reconnect.

Supply the specific device's `publishCapabilities` snapshot from your backend. It maps
`GET /v1/devices/{deviceId}/capabilities` → `.capabilities`: `microphoneInjection`,
`cameraInjection`, and `mediaInjectionTransport`. Do not put a privileged API key in the app or use
a stream token to query that endpoint. Support is separate from the server's permission decision.
The configuration is fixed for the first offer; use a new session and fresh token to change it.

```kotlin
val session = DeviceStreamSession(context, credentials, scope, StreamConfig(
    publishCapabilities = DevicePublishCapabilities(
        microphoneInjection = true, // from this device's backend response
        cameraInjection = true,
        mediaInjectionTransport = "direct",
    ),
))
// Headless hosts must already hold RECORD_AUDIO; the built-in pill requests it after acceptance:
val active = session.startPublish(PublishOptions(audio = true, video = false))
session.stopPublish() // idempotent; no server acknowledgement required
```

Declare `android.permission.RECORD_AUDIO` in the embedding app's manifest and, if using camera,
`android.permission.CAMERA`. The built-in pill uses the host Activity Result registry to request runtime permission only after
the device accepts `publishStart`. Refused or silent requests never prompt or capture. Headless
hosts supply existing grants or use `startPublishWithPermissions` to run their permission flow
only after acceptance. Audio-only needs no camera permission. The SDK checks actual grants before
opening inputs and never launches a permission dialog from application context. Publishing stops
when the host app enters the background; foregrounding never resumes it. No foreground service is added.

`session.publishState` is a `StateFlow<PublishState>` with phases `Idle`, `Capturing`, `Starting`,
`Accepted`, `Active`, `Stopped`, and `Error`. Start returns only after server `active` and ordered
microphone/camera attachment both succeed. Stop cancels a pending start with `PublishException`
(`failure.reason == PublishCancelled`) and preserves a `Stopped/stopped_by_client` snapshot,
without emitting an error. Cancelling the coroutine awaiting start also stops capture, following
Kotlin cancellation semantics. A second concurrent start fails without disturbing the current publish.
To change camera kinds, stop and start explicitly; every start gets a fresh media UUID.

Other failures throw `PublishException` carrying `PublishFailure` and appear once on `session.errors`
with `operation == "publish"`, the current viewer attempt, `final == true`, and optional
`mediaSessionId`, `detail`, `serverReason`. `StreamErrorReason.wireValue` exposes the common contract
spelling. Explicit server `forbidden` maps to `PublishNotAllowed` / `publish_not_allowed`, including
view-only refusal. Silence for five seconds is `PublishUnsupported/response_timeout`; acceptance
without active for thirty seconds (including the permission prompt) is `PublishTimeout/active_timeout`. Permission refusal is
`PermissionDenied/capture_permission`, distinct from server authorization. Failures release all
capture and preserve the viewer; socket loss, peer loss, close and scope cancellation also release
capture. No publish retries are scheduled. Same-peer ICE refresh preserves an active publication.

A direct microphone capability is required. Camera additionally needs device support and a usable
local H.264 encoder (profile 42e0, 42c0, 4d00 or 640c, level 0x1f or higher; the platform level is preserved). Native trackless first-offer senders are checked for SSRCs and compatible
Opus/H.264 codecs. Unsupported negotiation reports `PublishUnsupported` while viewing continues;
requested camera never silently falls back to microphone alone. No dummy capture, second peer,
publish-time renegotiation, extra stream token, or REST publish request is used.

## Built-in controls

`DeviceStream` overlays a floating microphone/camera pill at bottom centre. Live icons use the theme's
red `destructive` token. Starting disables both buttons. Camera is offered only when supported.
Tap microphone to start/stop all local inputs; tap camera to start audio+video, or to stop/start the
current session with camera toggled. Server permission remains authoritative regardless of
`hasControl`. Missing/incompatible device capabilities hide the pill. Set
`showPublishControls = false` for a headless/custom UI. Removing `DeviceStream` stops publication
without closing the viewer session, reporting terminal `disconnected` once. The pill fades to 50%
opacity after three seconds without interaction; pointer/touch near it restores full opacity.
Only buttons receive taps; decorative padding and background pass input through to the stream.
Status text floats separately above the fixed-height pill.

The optional `requestPublishPermissions: suspend (PublishOptions) -> Unit` hook lets the host run its
permission flow after the device accepts a pill-initiated publish request, before capture.
Return after the grant result, even when denied; the SDK then reports the structured permission
failure. By default the pill uses the host Activity Result registry, skipping already granted
permissions; applications can override this behavior. It is never invoked on mount, reconnect,
refusal, silence, or stop. Keep custom permission launchers in the host Activity/Fragment lifecycle.

All captions and accessible button names use `StreamLabels`: `microphone`, `camera`,
`stopMicrophone`, `stopCamera`, `publishStarting`, `publishPermissionDenied`, `publishNotAllowed`,
and `publishFailed` (unsupported publication hides the pill). Theme and label overrides apply to the pill as to other
stream chrome. The SDK does not render raw server reason strings.

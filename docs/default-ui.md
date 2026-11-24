← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Default UI and white-labeling

The optional `:stream-compose` module uses Compose foundation only. It does not read Material 3
colors or typography, show a logo, or put a product name in the UI. `DeviceStream` adds a bottom-left
status pill while connecting/reconnecting, an unavailable message with Retry, and a navigation bar
below the video. Retry calls the same session recovery path as a custom UI. Reconnecting labels take
precedence over `labels.connecting`; connected video remains visible with `hasControl = false`, which
also disables the navigation buttons. The standalone `StreamNavigationBar(onAction)` emits `SystemKey`
BACK/HOME/RECENT (4/3/187). `StreamStatusPill(label)` is reusable independently.

```kotlin
StreamTheme { // light by default; opt in with followSystem = true
    DeviceStream(
        session = session,
        modifier = Modifier.fillMaxSize(),
        labels = StreamLabels(connecting = "Preparing", retry = "Try again"),
        navigationBar = {}, // omit, or provide { state -> ... }
        // placeholder = { status, labels -> YourPlaceholder(status, labels) },
        // unavailable = { retry, labels -> YourUnavailable(retry, labels) },
    )
}
```

`StreamLabels` overrides every stream string, including accessible navigation names. `TaskApprovalLabels`
has the same twelve keys as React: `paymentCardTitle`, `waiting`, `card`, `app`, `domain`, `timeLeft`,
`expired`, `approve`, `deny`, `viewSwitch`, `details`, `device`. Defaults are English; `{time}` in
`timeLeft` and `{attempt}`/`{max}` in `StreamLabels.reconnectingAttempt` are replaceable placeholders.
`labels.disconnected` (default `disconnected`) is shown for the first reconnect after an open session dropped; other reconnects use `reconnecting` / `reconnectingAttempt`.
Backend-provided fact values and error messages remain the integrator's responsibility to localize.

Override individual theme tokens via `copy`, without modifying the rest:

```kotlin
val tokens = StreamThemeTokens.dark().copy(
    primary = Color(0xFF2563EB),
    primaryForeground = Color.White,
    radius = 12.dp,
    typography = StreamTypography(body = TextStyle(fontSize = 16.sp)),
)
StreamTheme(tokens) { DeviceStream(session, Modifier.fillMaxSize()) }
```

All thirteen React semantic colors are exposed: background, foreground, card, cardForeground,
muted, mutedForeground, border, ring, primary, primaryForeground, accent, accentForeground,
destructive, plus radius and body/caption/title typography. The ring, accent and accentForeground
tokens are reserved for focus/accent styling in custom slots; defaults do not currently read them. Slots can read `StreamTheme.tokens`. Light is the default even when the system is dark; use
`StreamTheme(tokens = StreamThemeTokens.dark())` or `StreamTheme(followSystem = true)` to opt in.
The defaults are React's `styles.css` values converted once: OKLCH → OKLab → linear sRGB using
CSS Color 4 matrices, apply the sRGB transfer function, clip each channel to [0, 1], round to 8 bits.
Light neutrals are #FFFFFF/#0A0A0A/#F5F5F5/#737373/#E5E5E5/#A1A1A1/#171717/#FAFAFA;
destructive is #E7000B. Dark adds #262626 and destructive #FF6467; its border retains 10% white.
Native dp/sp replace CSS px; the base radius is 8 dp; buttons use radius × 0.75 (6 dp), cards radius + 4 (12 dp) and text is 12/14/16 sp. Native buttons use
48 dp touch targets. Each property is independently replaceable.

The plain `DeviceStreamView` remains the View API for custom hosts. Navigation, pill, approval card,
and default overlays are Compose-only. A View host can embed them in `ComposeView` or build its own
chrome on the headless core.


## Microphone and camera pill

With a direct microphone `publishCapabilities` snapshot in the session config, `DeviceStream`
overlays mic/camera controls at bottom centre, without changing video layout. Camera also requires
local H.264 support. Live icons use `StreamTheme.tokens.destructive`; starting disables controls.
Set `showPublishControls = false` to hide them. Use `requestPublishPermissions` for your host runtime
permission flow before capture. All publish captions/accessibility names use `StreamLabels`,
including permission-denied and server-refused messages. See [Publishing](publishing.md).

Publish buttons use the host Activity Result registry by default, after device acceptance. The pill's
background passes taps through to the stream. After three seconds of inactivity it fades to 50%
opacity; nearby pointer/touch activity restores it. Status text floats above the fixed-height pill.

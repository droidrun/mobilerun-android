← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Video and input

```kotlin
val streamView = DeviceStreamView(context).apply {
    attach(session)
    hasControl = true
}
container.addView(streamView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
session.start()

streamView.hideKeyboard()
streamView.sendSystemKey(SystemKey.Back) // also Home and Recent
// Tapping the video focuses it; show the soft keyboard explicitly:
streamView.showKeyboard()

// When this host is finished:
streamView.detach()          // does not close the session
session.close()
```

Use view methods on the main thread. Video fits within the view, preserving its aspect ratio and
rotation; letterbox areas and a view without video remain transparent. Removing the view from its
window releases its renderer; adding it again resumes rendering the attached session. Repeated
`attach` with the same session does nothing. The app owns the session's lifetime.

Compose uses the same view and input paths. Add `:stream-compose` and import
`ai.mobilerun.stream.compose.DeviceStream`; `:stream-android` has no Compose dependency:

```kotlin
DeviceStream(session = session, modifier = Modifier.fillMaxSize(), hasControl = true)
```

`hasControl = false` renders without sending input. It is a display-only switch, not a permission
check; the server decides access. Touch uses stable slots for up to ten fingers, coalesces movement
per frame and releases at the last position inside the video. Mouse drags use pointer -1; hover
uses only the hover channel. Wheel axes use Android's tick units, without the browser pixel scaling.
Hardware printable single-grapheme keys inject text; Enter, NumpadEnter, Tab, navigation, modifier and Ctrl/Command combinations inject
Android keycodes with React's Shift/Alt/Ctrl/Meta flags only; CapsLock XORs Shift for letters. Ctrl/Command+V pastes the local primary clipboard;
Ctrl/Command+M presses Menu. Single graphemes, including emoji and combining sequences, inject their produced text without normalization.
Keycode DOWNs track physical identity; only matched keyups send UP, using current modifiers. Text
and completed paste/menu shortcuts never produce orphan releases.
The IME requests per-character Latin input with the no-suggestions flag and the no-personalized-learning hint on Android 8+. CJK IMEs may still compose. Composition
stays local until `commitText` or `finishComposingText`; Backspace and editor actions send device keys. Committed CRLF (as one Enter), CR, LF and horizontal tabs become ENTER/TAB; other
committed control characters are dropped. Printable IME runs use INJECT_TEXT for every client. The local input buffer does not mirror the remote editor,
so remote selection, autocorrection and surrounding text are not exposed to the local IME.
Some emoji and CJK characters may not arrive on Cloud Android devices through text injection.

The host's Back button or gesture is **never forwarded automatically**. Apps may opt in from their
own Back handler, for example with AndroidX Activity's `OnBackPressedDispatcher`:

```kotlin
onBackPressedDispatcher.addCallback(this) {
    streamView.sendSystemKey(SystemKey.Back)
}
```

Decide in that handler when to navigate locally instead. `SystemKey.Home` and `SystemKey.Recent` are also
explicit actions. The Compose module supplies optional navigation chrome.


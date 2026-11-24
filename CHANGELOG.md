# Changelog

## 0.1.0 — 2026-11-24

- Device streaming with automatic reconnection and codec selection.
- Remote touch, mouse, keyboard, clipboard and Android navigation input.
- Optional default Compose UI, theme tokens, labels and custom slots.
- Task approvals with subscription updates, polling and decision handling.
- Inbound video and connection statistics.
- Visibility and network lifecycle recovery.
- Device screenshots with typed failure and timeout errors.
- Per-connect `tokenProvider` with single-use keys, `errors` flow with failure reasons, and a deprecation warning for the legacy static token (still reused on reconnect until the legacy hard cut).
- Microphone and camera publish: startPublish / stopPublish with publish state and reasons, and a built-in control over the stream when the device supports it.
- Agent skill skills/mobilerun-device-stream for integrating the SDK.

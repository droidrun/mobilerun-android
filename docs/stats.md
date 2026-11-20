← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Stream statistics and benchmark

`session.stats` exposes `ai.mobilerun.stream.core.StreamStats`. It is read-only and `null` while disconnected or after close. Collect it like the
status flow. While the host is visible and at least one collector observes it, every connected second it reports inbound video fps, decoded dimensions, codec MIME
type, payload bitrate in kbit/s, selected-pair RTT in ms, jitter-buffer delay per emitted frame,
packet loss, dropped frames, freezes, decode time per frame, local/remote ICE candidate types, and
time from the connection attempt to its first decoded frame. Missing platform fields remain null.
Bitrate and per-frame durations need two reports; each new attempt or sampling resume resets the counter baseline. Hidden, unobserved and non-connected sessions expose null and do not sample.
RTT uses the transport's selected candidate pair, rather than an arbitrary nominated pair.

The instrumentation-only benchmark opens five connections and takes ten input-latency samples per
connection. Supply a running emulator (`ANDROID_SERIAL`) or `BENCHMARK_AVD`, plus runtime credentials
and an output directory outside the repository:

```sh
STREAM_E2E_CREDENTIALS="$RUNTIME_CREDENTIALS" STREAM_BENCHMARK_OUT="$BENCHMARK_OUTPUT" \
  TAP_X=200 TAP_Y=600 scripts/benchmark.sh
```

`TAP_X/Y` are upright video pixels selecting an existing harmless launcher app icon. Each sample
returns Home, waits for four stable frames, then taps it. A quarter-scale renderer callback compares
4×4 pixel tiles with the baseline. The tap-response region is 64×64 upright-video pixels centered on
the tap; the first tile whose mean absolute luma difference reaches 4/255 ends input-to-pixel timing.
The separate app-launch metric observes the central picture (20–80% width, 20–60% height), away from
the dock icon. These are an app-launch stimulus's local response and central transition respectively,
not physical display scanout or an isolated network transit measurement. Ten steady-state stats
samples per connection are distinct flow emissions. Failed stability/change checks fail the benchmark.
`BENCHMARK_RUNS` and `BENCHMARK_SAMPLES` override the five-connection/ten-input defaults. Results include
both raw sample sets and separately labelled median/p90 columns. Credentials are copied only to the
test app's private storage at runtime and removed afterward. Sanitized native reports are a separate,
opt-in `NativeStatsCaptureTest` using the same instrumentation runner.

The separate `StreamHardeningTest` uses the same runner and credentials. It exercises host visibility,
network loss/restoration, a ten-minute soak (PSS growth bounded by the larger of 32 MiB or 25% of the
warmed baseline), native-peer teardown and session collection. Run it with
`STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.StreamHardeningTest scripts/run-e2e.sh stream-android`.
It restores emulator networking even after failure. A rejected initial device login is reported as `Device unavailable:`; it is not a successful
benchmark or soak result.


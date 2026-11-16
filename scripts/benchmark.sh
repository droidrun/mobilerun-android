#!/usr/bin/env bash
# Uses an existing emulator, or starts BENCHMARK_AVD. Requires Gradle and the Android SDK.
# The caller supplies runtime credentials and an output directory outside the repository.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${STREAM_BENCHMARK_OUT:?set STREAM_BENCHMARK_OUT outside the repository}"
mkdir -p "$out"
owned_serial=""
emulator_pid=""
cleanup() {
  if [ -n "$owned_serial" ]; then adb -s "$owned_serial" emu kill >/dev/null 2>&1 || true; fi
  if [ -n "$emulator_pid" ]; then
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
if [ -z "${ANDROID_SERIAL:-}" ]; then
  serials="$(adb devices | awk '$2 == "device" {print $1}')"
  if [ -z "$serials" ]; then
    avd="${BENCHMARK_AVD:?no emulator attached; set BENCHMARK_AVD}"
    port="${BENCHMARK_EMULATOR_PORT:-5580}"
    owned_serial="emulator-$port"
    "${ANDROID_HOME:?set ANDROID_HOME}/emulator/emulator" -avd "$avd" -port "$port" \
      -no-window -no-audio -no-boot-anim -no-snapshot-save > "$out/emulator.log" 2>&1 &
    emulator_pid=$!
    export ANDROID_SERIAL="$owned_serial"
    for ((i=0; i<120; i++)); do
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
      kill -0 "$emulator_pid" 2>/dev/null || { echo "emulator stopped" >&2; exit 1; }
      sleep 1
    done
    [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || exit 1
  elif [ "$(echo "$serials" | wc -l | tr -d ' ')" = 1 ]; then
    export ANDROID_SERIAL="$serials"
  else
    echo "multiple devices attached; set ANDROID_SERIAL" >&2
    exit 2
  fi
fi
export STREAM_E2E_OUT="$out"
export STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.StreamBenchmarkTest
export BENCHMARK_RUNS="${BENCHMARK_RUNS:-5}"
export BENCHMARK_SAMPLES="${BENCHMARK_SAMPLES:-10}"
rm -f "$out/benchmark.json"
result=0
"$root/scripts/run-e2e.sh" stream-android > "$out/benchmark-run.log" 2>&1 || result=$?
python3 - "$out" "$result" <<'PY'
import json, pathlib, sys
out = pathlib.Path(sys.argv[1])
status = int(sys.argv[2])
lines = ["# Stream benchmark", "",
         "Method: quarter-scale renderer callback; tap DOWN to maximum 4x4-tile mean absolute luma difference >=4/255 in a 64x64 upright-video-pixel region around the tap. Four stable frames precede input. App-launch transition is measured separately in the central picture (20-80% width, 20-60% height), away from the dock icon. Stimulus: launch a harmless app from Home. Excludes physical display scanout. Steady statistics use ten distinct flow emissions per connection.", "",
         "| Run | First frame ms | FPS | kbit/s | RTT ms | ICE local/remote | Tap median ms | Tap p90 ms | App-launch median ms | App-launch p90 ms |",
         "|---|---:|---:|---:|---:|---|---:|---:|---:|---:|"]
data = out / 'benchmark.json'
def value(v):
    return 'n/a' if v is None else (f'{v:.2f}' if isinstance(v, (int, float)) else str(v))
if data.exists():
    for row in json.loads(data.read_text()):
        keys = ['run','firstFrameMs','fps','bitrateKbps','rttMs','candidateType','latencyMedianMs','latencyP90Ms','appLaunchMedianMs','appLaunchP90Ms']
        lines.append('| ' + ' | '.join(value(row.get(k)) for k in keys) + ' |')
if status:
    log = (out / 'benchmark-run.log').read_text(errors='replace')
    reason = 'Device unavailable: HTTP 401/404' if 'Device unavailable:' in log else 'FAILED: see benchmark-run.log'
    lines += ['', reason + '; no complete benchmark claimed.']
else:
    lines += ['', 'Completed. Ten latency samples per connection by default; median and nearest-rank p90.']
(out / 'benchmark-results.md').write_text('\n'.join(lines) + '\n')
PY
tail -30 "$out/benchmark-run.log"
exit "$result"

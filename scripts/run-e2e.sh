#!/usr/bin/env bash
# Copyright 2026 Mobilerun
# SPDX-License-Identifier: Apache-2.0

# Runs either Android module on an existing emulator; credentials are never bundled.
#
# Usage: STREAM_E2E_CREDENTIALS=<json> [STREAM_E2E_OUT=<dir>] [TAP_X=<px> TAP_Y=<px>] \
#          scripts/run-e2e.sh stream-android
#
# Set STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.DeviceStreamViewE2eTest for the view
# scenario, with TAP_X/Y (app icon) and TEXT_X/Y (text field) in upright video pixels.
# OPEN_URL selects a long document for the scroll assertion.
# STREAM_E2E_CREDENTIALS is a JSON file with `streamUrl` and `streamToken`. It is piped over stdin into
# the test app's private files directory; it never enters argv, the repository or the APK.
# Needs a running emulator or device (adb) and JAVA_HOME / ANDROID_HOME set.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
module="${1:?usage: scripts/run-e2e.sh stream-android|stream-compose}"
case "$module" in
  stream-android) pkg=ai.mobilerun.stream.android.test; default_class=ai.mobilerun.stream.android.DeviceStreamE2eTest ;;
  stream-compose) pkg=ai.mobilerun.stream.compose.test; default_class=ai.mobilerun.stream.compose.DefaultUiE2eTest ;;
  *) echo "unknown module" >&2; exit 2 ;;
esac
creds="${STREAM_E2E_CREDENTIALS:-}"
out="${STREAM_E2E_OUT:-$root/$module/build/e2e-out}"
mkdir -p "$out"

gallery=false
[ "${STREAM_E2E_TEST_CLASS:-}" = ai.mobilerun.stream.compose.DefaultUiGalleryTest ] && gallery=true
if [ "$gallery" = false ] && [ ! -f "$creds" ]; then
  echo "Credentials file missing" >&2
  exit 2
fi

cd "$root"
./gradlew ":$module:installDebugAndroidTest" --no-daemon

trap 'adb shell run-as "$pkg" rm -f files/stream-e2e.json >/dev/null 2>&1 || true' EXIT
adb shell run-as "$pkg" rm -rf files/e2e-out
adb shell run-as "$pkg" mkdir -p files/e2e-out
if [ "$gallery" = false ]; then
python3 -I - "$creds" <<'PY' | adb shell "run-as $pkg sh -c 'cat > files/stream-e2e.json'"
import json, sys
d = json.load(open(sys.argv[1]))
print(json.dumps({"streamUrl": d["streamUrl"], "streamToken": d["streamToken"]}))
PY
fi

extra=()
[ -n "${VIDEO_CODEC:-}" ] && extra+=(-e videoCodec "$VIDEO_CODEC")
[ -n "${TAP_X:-}" ] && extra+=(-e tapX "$TAP_X" -e tapY "${TAP_Y:?set TAP_Y with TAP_X}")
[ -n "${TEXT_X:-}" ] && extra+=(-e textX "$TEXT_X" -e textY "${TEXT_Y:?set TEXT_Y with TEXT_X}")
[ -n "${OPEN_URL:-}" ] && extra+=(-e openUrl "$OPEN_URL")
[ -n "${BENCHMARK_RUNS:-}" ] && extra+=(-e benchmarkRuns "$BENCHMARK_RUNS")
[ -n "${BENCHMARK_SAMPLES:-}" ] && extra+=(-e benchmarkSamples "$BENCHMARK_SAMPLES")

adb logcat -c
status=0
test_class="${STREAM_E2E_TEST_CLASS:-$default_class}"
instrument=(am instrument -w -e class "$test_class"
  ${extra[@]+"${extra[@]}"} "$pkg/androidx.test.runner.AndroidJUnitRunner")
# adb joins arguments into a remote shell command. Quote each one for that shell too.
quoted=()
for argument in "${instrument[@]}"; do
  quoted+=("'${argument//\'/\'\\\'\'}'")
done
adb shell "${quoted[*]}" | tee "$out/instrument.log" || status=$?

adb logcat -d -s StreamE2E:I StreamInputE2E:I > "$out/stream.log" || true
adb exec-out run-as "$pkg" tar -cf - -C files/e2e-out . > "$out/screenshots.tar"
tar -xf "$out/screenshots.tar" -C "$out"
rm "$out/screenshots.tar"
grep -q "OK (" "$out/instrument.log" || status=1
if grep -q 'Device unavailable:' "$out/instrument.log"; then status=2; fi
exit "$status"

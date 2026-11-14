#!/usr/bin/env bash
# Runs the emulator end-to-end test of :stream-android against a live cloud device.
#
# Usage: STREAM_E2E_CREDENTIALS=<json> [STREAM_E2E_OUT=<dir>] [TAP_X=<px> TAP_Y=<px>] \
#          stream-android/scripts/run-e2e.sh
#
# STREAM_E2E_CREDENTIALS is a JSON file with `streamUrl` and `streamToken`. It is piped over stdin into
# the test app's private files directory; it never enters argv, the repository or the APK.
# Needs a running emulator or device (adb) and JAVA_HOME / ANDROID_HOME set.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
creds="${STREAM_E2E_CREDENTIALS:?set STREAM_E2E_CREDENTIALS to the credentials json}"
out="${STREAM_E2E_OUT:-$root/stream-android/build/e2e-out}"
pkg=ai.mobilerun.stream.android.test
mkdir -p "$out"

[ -f "$creds" ] || { echo "PENDING: credentials file missing" >&2; exit 2; }

cd "$root"
./gradlew :stream-android:installDebugAndroidTest --no-daemon

adb shell run-as "$pkg" mkdir -p files/e2e-out
python3 -I - "$creds" <<'PY' | adb shell "run-as $pkg sh -c 'cat > files/stream-e2e.json'"
import json, sys
d = json.load(open(sys.argv[1]))
print(json.dumps({"streamUrl": d["streamUrl"], "streamToken": d["streamToken"]}))
PY

extra=()
[ -n "${TAP_X:-}" ] && extra+=(-e tapX "$TAP_X" -e tapY "${TAP_Y:?set TAP_Y with TAP_X}")

adb logcat -c
status=0
adb shell am instrument -w -e class ai.mobilerun.stream.android.DeviceStreamE2eTest \
  ${extra[@]+"${extra[@]}"} "$pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrument.log" || status=$?

adb logcat -d -s StreamE2E:I > "$out/stream.log" || true
for name in 1-before-tap 2-after-tap 3-after-reconnect 4-after-refresh; do
  adb exec-out run-as "$pkg" cat "files/e2e-out/$name.png" > "$out/$name.png" 2>/dev/null || rm -f "$out/$name.png"
done
# Remove the credentials from the device again.
adb shell run-as "$pkg" rm -f files/stream-e2e.json
grep -q "OK (" "$out/instrument.log" || status=1
exit "$status"

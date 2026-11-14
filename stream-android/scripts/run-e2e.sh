#!/usr/bin/env bash
# Runs the emulator end-to-end test of :stream-android against a live cloud device.
#
# Usage: STREAM_E2E_CREDENTIALS=<json> [STREAM_E2E_OUT=<dir>] [TAP_X=<px> TAP_Y=<px>] \
#          stream-android/scripts/run-e2e.sh
#
# Set STREAM_E2E_TEST_CLASS=ai.mobilerun.stream.android.DeviceStreamViewE2eTest for the view
# scenario, with TAP_X/Y (app icon) and TEXT_X/Y (text field) in upright video pixels.
# OPEN_URL selects a long page for the scroll assertion.
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

trap 'adb shell run-as "$pkg" rm -f files/stream-e2e.json >/dev/null 2>&1 || true' EXIT
adb shell run-as "$pkg" rm -rf files/e2e-out
adb shell run-as "$pkg" mkdir -p files/e2e-out
python3 -I - "$creds" <<'PY' | adb shell "run-as $pkg sh -c 'cat > files/stream-e2e.json'"
import json, sys
d = json.load(open(sys.argv[1]))
print(json.dumps({"streamUrl": d["streamUrl"], "streamToken": d["streamToken"]}))
PY

extra=()
[ -n "${TAP_X:-}" ] && extra+=(-e tapX "$TAP_X" -e tapY "${TAP_Y:?set TAP_Y with TAP_X}")
[ -n "${TEXT_X:-}" ] && extra+=(-e textX "$TEXT_X" -e textY "${TEXT_Y:?set TEXT_Y with TEXT_X}")
[ -n "${OPEN_URL:-}" ] && extra+=(-e openUrl "$OPEN_URL")

adb logcat -c
status=0
test_class="${STREAM_E2E_TEST_CLASS:-ai.mobilerun.stream.android.DeviceStreamE2eTest}"
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
exit "$status"

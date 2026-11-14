#!/usr/bin/env bash
# Runtime-only credentials and screenshots; requires a running Android emulator and ANDROID_SERIAL.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
creds="${STREAM_E2E_CREDENTIALS:?set STREAM_E2E_CREDENTIALS}"
out="${STREAM_E2E_OUT:-$root/stream-compose/build/e2e-out}"
pkg=ai.mobilerun.stream.compose.test
mkdir -p "$out"
cd "$root"
./gradlew :stream-compose:installDebugAndroidTest --no-daemon
trap 'adb shell run-as "$pkg" rm -f files/stream-e2e.json >/dev/null 2>&1 || true' EXIT
adb shell run-as "$pkg" rm -rf files/e2e-out
adb shell run-as "$pkg" mkdir -p files/e2e-out
python3 -I - "$creds" <<'PY' | adb shell "run-as $pkg sh -c 'cat > files/stream-e2e.json'"
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
print(json.dumps({"streamUrl": d["streamUrl"], "streamToken": d["streamToken"]}))
PY
status=0
adb shell am instrument -w -e class "${STREAM_E2E_TEST_CLASS:-ai.mobilerun.stream.compose.DefaultUiE2eTest}" \
    "$pkg/androidx.test.runner.AndroidJUnitRunner" > "$out/instrument.log" 2>&1 || status=$?
cat "$out/instrument.log"
adb exec-out run-as "$pkg" tar -cf - -C files/e2e-out . > "$out/screenshots.tar"
tar -xf "$out/screenshots.tar" -C "$out"
rm "$out/screenshots.tar"
grep -q 'OK (' "$out/instrument.log" || status=1
exit "$status"

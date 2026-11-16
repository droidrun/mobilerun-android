#!/usr/bin/env bash
# Build and run the interactive sample; credentials travel only over stdin.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
creds="${1:?usage: scripts/run-sample.sh CREDENTIALS_JSON}"
[ -f "$creds" ] || { echo "Credentials file not found" >&2; exit 2; }
creds="$(cd "$(dirname "$creds")" && pwd)/$(basename "$creds")"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK directory}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
emulator="$ANDROID_HOME/emulator/emulator"
avd="${SAMPLE_AVD:-$("$emulator" -list-avds | sed -n '/^[A-Za-z0-9_.-][A-Za-z0-9_.-]*$/p' | head -1)}"
[ -n "$avd" ] || { echo "Create an Android API 35+ AVD in Android Studio first" >&2; exit 2; }
port="${SAMPLE_EMULATOR_PORT:-5582}"
export ANDROID_SERIAL="emulator-$port"
# Do not install into or terminate an emulator belonging to another session.
if adb devices | awk '{print $1}' | grep -qx "$ANDROID_SERIAL"; then
  echo "Sample emulator port is occupied; choose SAMPLE_EMULATOR_PORT" >&2
  exit 2
fi
pkg=ai.mobilerun.sample
pid=""
cleanup() {
  if [ -n "$pid" ]; then
    adb shell run-as "$pkg" rm -f files/stream-e2e.json >/dev/null 2>&1 || true
    adb emu kill >/dev/null 2>&1 || true
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
cd "$root"
./gradlew :sample:assembleDebug --no-daemon
log="${TMPDIR:-/tmp}/mobilerun-sample-emulator-$$.log"
"$emulator" -avd "$avd" -port "$port" -no-snapshot-save -no-boot-anim > "$log" 2>&1 &
pid=$!
for ((i=0; i<180; i++)); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  kill -0 "$pid" 2>/dev/null || { echo "Emulator stopped; see $log" >&2; exit 1; }
  sleep 1
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "Emulator boot timed out" >&2; exit 1; }
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
adb shell am force-stop "$pkg"
adb shell run-as "$pkg" mkdir -p files
adb shell "run-as $pkg sh -c 'cat > files/stream-e2e.json'" < "$creds"
adb shell am start -n "$pkg/.MainActivity" >/dev/null
echo "Sample launched. Press Ctrl-C to stop the emulator and release the build lock."
while kill -0 "$pid" 2>/dev/null; do sleep 2; done

#!/bin/zsh
# Emulator session for the stream spike: boot -> install -> configure -> run scenarios -> shut down.
# Usage: SPIKE_SECRETS=<credentials.json> scripts/run-spike.sh <codec> <scenario...>
#   codec:     default | h265 | h264
#   scenario:  probe | ttff3 | tap X Y TAG | wskill | reconnect | icerestart | wait SECONDS
# Credentials are read at run time from the secrets file and piped over stdin into the app's private
# files dir (run-as); the token never touches argv, logs, the repo or the APK.
set -u
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
ROOT=${0:A:h:h}
OUT=${SPIKE_OUT:-$ROOT/build/spike-out}
SECRETS=${SPIKE_SECRETS:?set SPIKE_SECRETS to the credentials json (deviceId, streamUrl, streamToken)}
APK=${SPIKE_APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}
PKG=ai.mobilerun.spike
AVD=spike_stream
CODEC=${1:-default}; shift
mkdir -p $OUT

EMU_PID=""
cleanup() {
  adb emu kill >/dev/null 2>&1
  [[ -n $EMU_PID ]] && kill $EMU_PID >/dev/null 2>&1
  sleep 3
  [[ -n $EMU_PID ]] && kill -9 $EMU_PID >/dev/null 2>&1
  echo "emulator stopped"
}
trap cleanup EXIT INT TERM

[[ -f $SECRETS ]] || { echo "STOPPED: credentials file missing"; exit 2; }

if ! avdmanager list avd | grep -q "Name: $AVD"; then
  echo no | avdmanager create avd -n $AVD -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
fi
emulator -avd $AVD -no-window -no-audio -memory 2048 -no-snapshot -no-boot-anim -gpu swiftshader_indirect >$OUT/emulator.log 2>&1 &
EMU_PID=$!
adb wait-for-device
until [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do sleep 2; done
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
echo "emulator booted"

adb install -r -t $APK
write_config() {
  python3 -I - "$SECRETS" "$CODEC" <<'PY' | adb shell "run-as $PKG sh -c 'mkdir -p files; cat > files/spike-config.json'"
import json, sys
d = json.load(open(sys.argv[1]))
print(json.dumps({"streamUrl": d["streamUrl"], "streamToken": d["streamToken"], "codec": sys.argv[2]}))
PY
}
write_config

start_app() { adb logcat -c; adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity >/dev/null; }
wait_metric() { # pattern timeout_s
  local i=0; while (( i < $2 )); do
    adb logcat -d -s Spike:I | grep -q -- "$1" && return 0
    sleep 1; ((i++))
  done; return 1
}
shot() { adb exec-out screencap -p > "$SPIKE_SHOTS/${CODEC}-$1.png"; echo "shot $1"; }
SPIKE_SHOTS=${SPIKE_SHOTS:-$OUT/shots}; mkdir -p $SPIKE_SHOTS
dump_log() { adb logcat -d -b crash > "$OUT/${CODEC}-$1.crash.log" 2>&1; adb logcat -d -s Spike:I | sed 's/^.*METRIC /METRIC /' | grep METRIC > "$OUT/${CODEC}-$1.log"; tail -40 "$OUT/${CODEC}-$1.log"; }

while (( $# )); do
  sc=$1; shift
  echo "=== scenario $sc codec=$CODEC"
  case $sc in
    probe)
      start_app; wait_metric first_video_frame 60; echo "first-frame wait exit=$?"; sleep 8; shot probe; dump_log probe ;;
    ttff3)
      for n in 1 2 3; do start_app; wait_metric first_video_frame 60; echo "run $n wait exit=$?"; sleep 4; dump_log ttff-$n; done ;;
    tap)
      x=$1; y=$2; tag=$3; shift 3
      start_app; wait_metric first_video_frame 60; sleep 6
      shot tap-$tag-before; adb shell input tap $x $y; sleep 3; shot tap-$tag-after; dump_log tap-$tag ;;
    wskill)
      start_app; wait_metric first_video_frame 60; sleep 3
      adb shell am broadcast -a ai.mobilerun.spike.KILL_WS >/dev/null; sleep 20; shot after-ws-kill; dump_log wskill ;;
    reconnect)
      start_app; wait_metric first_video_frame 60; sleep 3
      adb shell am broadcast -a ai.mobilerun.spike.KILL_WS >/dev/null; sleep 4
      adb shell am broadcast -a ai.mobilerun.spike.RECONNECT >/dev/null
      sleep 12; shot after-reconnect; dump_log reconnect ;;
    icerestart)
      start_app; wait_metric first_video_frame 60; sleep 3
      adb shell am broadcast -a ai.mobilerun.spike.ICE_RESTART >/dev/null; sleep 10; shot after-ice-restart; dump_log icerestart ;;
    wait) sleep $1; shift ;;
    *) echo "unknown scenario $sc" ;;
  esac
done

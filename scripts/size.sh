#!/bin/zsh
# Release (R8, arm64) APK size with and without the WebRTC dependency. 
cd "$(dirname "$0")/.."
D=${1:-build/size}; mkdir -p $D
./scripts/build.sh clean :app:assembleRelease && cp app/build/outputs/apk/release/app-release.apk $D/with-webrtc.apk
./scripts/build.sh -Pbaseline=true clean :app:assembleRelease && cp app/build/outputs/apk/release/app-release.apk $D/baseline.apk
ls -l $D/*.apk

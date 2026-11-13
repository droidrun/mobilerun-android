#!/bin/zsh
# Usage: scripts/build.sh <gradle args...>
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd "$(dirname "$0")/.."
./gradlew --no-daemon "$@"

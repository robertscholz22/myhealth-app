#!/usr/bin/env bash
# Runs the instrumented tests on ONE named device only.
#
# AGP's connectedDebugAndroidTest installs the app + test APK on EVERY device `adb devices`
# lists and uninstalls them afterwards — with the owner's phone attached next to the emulator
# that removed the production app and all its data (2026-09-13). Never call
# `./gradlew :app:connectedDebugAndroidTest` directly; use this script, which refuses to run
# unless exactly one target is named and forces Gradle to that serial.
set -euo pipefail
export JAVA_HOME=${JAVA_HOME:-/home/robert/jdk/current}
export ANDROID_HOME=${ANDROID_HOME:-/home/robert/android-sdk}
export GRADLE_OPTS=${GRADLE_OPTS:-"-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m"}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL=${1:-${ANDROID_SERIAL:-emulator-5554}}
case "$SERIAL" in
  emulator-*) ;;
  *) echo "refusing: instrumented tests only run on an emulator (got '$SERIAL')" >&2; exit 2 ;;
esac
"$ANDROID_HOME/platform-tools/adb" -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "device $SERIAL not attached" >&2; exit 1; }
export ANDROID_SERIAL="$SERIAL"
cd "$ROOT"
exec ./gradlew :app:connectedDebugAndroidTest "${@:2}"

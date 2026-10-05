#!/usr/bin/env bash
# Headless emulator harness for MyHealth runtime verification (PLAN P10.1).
# Usage: tools/emu.sh up | down | status | install | grant | seed | logcat | reset
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/home/robert/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=${JAVA_HOME:-/home/robert/jdk/current}
AVD=${AVD:-myhealth_api35}
SERIAL=${SERIAL:-emulator-5554}
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.myhealth
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG=${EMU_LOG:-/tmp/myhealth-emu.log}

adbs() { "$ADB" -s "$SERIAL" "$@"; }

case "${1:-}" in
  up)
    if adbs shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then echo "already up"; exit 0; fi
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot -port "${SERIAL#emulator-}" >"$LOG" 2>&1 &
    t=0; until adbs shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; do
      sleep 3; t=$((t+3)); [ $t -ge 300 ] && { echo "BOOT TIMEOUT"; tail -20 "$LOG"; exit 1; }
    done
    adbs shell settings put global window_animation_scale 0 >/dev/null
    adbs shell settings put global transition_animation_scale 0 >/dev/null
    adbs shell settings put global animator_duration_scale 0 >/dev/null
    adbs shell input keyevent 82 >/dev/null 2>&1 || true
    echo "BOOTED in ${t}s ($(adbs shell getprop ro.build.version.release | tr -d '\r'))"
    ;;
  down) adbs emu kill >/dev/null 2>&1 || true; echo "stopped" ;;
  status) adbs shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$' && echo up || echo down ;;
  install)
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$APK" ] || { echo "no APK, run ./gradlew :app:assembleDebug"; exit 1; }
    adbs install -r -g "$APK" | tail -1
    ;;
  grant)
    for p in android.permission.CAMERA android.permission.POST_NOTIFICATIONS; do adbs shell pm grant $PKG $p 2>/dev/null || true; done
    # Health permissions can only be granted through the Health Connect UI or the permission contract.
    echo "granted runtime permissions (health permissions must be granted in-app)"
    ;;
  seed)
    adbs shell am broadcast -a com.myhealth.seeder.SEED -p com.myhealth.seeder --ez clear true --ei days "${2:-45}" | tail -1
    ;;
  logcat) adbs logcat -d -v time | grep -E "AndroidRuntime|MyHealth|FATAL|E/$PKG|com.myhealth" | tail -${2:-60} ;;
  clearlog) adbs logcat -c; echo cleared ;;
  reset) adbs shell pm clear $PKG | tail -1 ;;
  *) echo "usage: $0 up|down|status|install|grant|seed [days]|logcat [n]|clearlog|reset"; exit 2 ;;
esac

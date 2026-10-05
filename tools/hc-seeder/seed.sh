#!/usr/bin/env bash
# HC Seeder control script. Builds/installs the seeder app and drives it via broadcasts.
# Usage:
#   seed.sh up            - build debug APK and install on emulator-5554
#   seed.sh seed [days]   - broadcast SEED (default 45 days) and wait for "SEED DONE"
#   seed.sh clear         - broadcast CLEAR and wait for "CLEAR DONE"
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE="${DEVICE:-emulator-5554}"
PKG="com.myhealth.seeder"
export JAVA_HOME="${JAVA_HOME:-/home/robert/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-/home/robert/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

cmd="${1:-}"

log_wait_for() {
    # Poll `adb logcat -d -s HcSeeder` until $1 appears in the output, or 120s pass.
    local needle="$1"
    local waited=0
    local out=""
    while [ "$waited" -lt 120 ]; do
        out="$("$ADB" -s "$DEVICE" logcat -d -s HcSeeder:V 2>/dev/null || true)"
        if echo "$out" | grep -q "$needle"; then
            echo "$out" | grep "$needle"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    echo "Timed out after 120s waiting for '$needle'. Last HcSeeder log lines:" >&2
    echo "$out" | tail -40 >&2
    return 1
}

case "$cmd" in
    up)
        echo "Building :app:assembleDebug in $HERE ..."
        (cd "$HERE" && ./gradlew --no-daemon :app:assembleDebug)
        APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
        echo "Installing $APK on $DEVICE ..."
        "$ADB" -s "$DEVICE" install -r -g "$APK"
        echo "Installed. Note: -g may not actually grant Health Connect permissions on this"
        echo "platform. Run 'seed.sh seed' next; if it reports permission-denied for every"
        echo "type, open the HC Seeder app on the device and tap 'Grant permissions'."
        ;;
    seed)
        DAYS="${2:-45}"
        # Clear old logcat so we only match this run's summary line.
        "$ADB" -s "$DEVICE" logcat -c
        echo "Broadcasting SEED days=$DAYS ..."
        "$ADB" -s "$DEVICE" shell am broadcast -a com.myhealth.seeder.SEED -p "$PKG" --include-stopped-packages --ei days "$DAYS"
        echo "Waiting for SEED DONE (up to 120s) ..."
        if log_wait_for "SEED DONE"; then
            LAST_LINE="$("$ADB" -s "$DEVICE" logcat -d -s HcSeeder:V | grep 'SEED DONE' | tail -1)"
            if echo "$LAST_LINE" | grep -qi 'no permission\|SecurityException' \
               || ! echo "$LAST_LINE" | grep -qE '[1-9][0-9]*'; then
                cat <<'EOF'

All (or most) record types reported 0 / permission-denied. adb install -r -g does not
reliably grant Health Connect permissions on this platform. To fix:
  1. On the emulator, open the "HC Seeder" app (adb shell am start -n com.myhealth.seeder/.MainActivity).
  2. Tap "Grant permissions" and allow all requested data types in the Health Connect UI.
  3. Re-run: bash tools/hc-seeder/seed.sh seed 45
EOF
            fi
            exit 0
        else
            exit 1
        fi
        ;;
    clear)
        "$ADB" -s "$DEVICE" logcat -c
        echo "Broadcasting CLEAR ..."
        "$ADB" -s "$DEVICE" shell am broadcast -a com.myhealth.seeder.CLEAR -p "$PKG" --include-stopped-packages
        echo "Waiting for CLEAR DONE (up to 120s) ..."
        log_wait_for "CLEAR DONE"
        ;;
    *)
        echo "Usage: $0 {up|seed [days]|clear}" >&2
        exit 1
        ;;
esac

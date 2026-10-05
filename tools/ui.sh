#!/usr/bin/env bash
# UI driving helpers for the emulator (PLAN P10.3).
# Usage: tools/ui.sh shot <name> | dump | tap <x> <y> | tapt "<text>" | tapd "<content-desc>" | type "<text>" | back | home | swipe up|down | texts | wait "<text>" [sec]
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/home/robert/android-sdk}
SERIAL=${SERIAL:-emulator-5554}
ADB="$ANDROID_HOME/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHOTS="$ROOT/docs/screenshots"
adbs() { "$ADB" -s "$SERIAL" "$@"; }
dump() { adbs shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adbs shell cat /sdcard/ui.xml; }
center() { # $1 = bounds like [x1,y1][x2,y2]
  echo "$1" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}
find_bounds() { # $1 attr name, $2 value (substring match)
  dump | grep -oE "<node[^>]*$1=\"[^\"]*$2[^\"]*\"[^>]*>" | head -1 | grep -oE 'bounds="[^"]+"' | cut -d'"' -f2
}
case "${1:-}" in
  shot) mkdir -p "$SHOTS"; adbs exec-out screencap -p > "$SHOTS/${2:-shot}.png"; echo "$SHOTS/${2:-shot}.png" ;;
  dump) dump | grep -oE '(text|content-desc)="[^"]+"' | grep -v '=""' | sort -u ;;
  texts) dump | grep -oE 'text="[^"]+"' | cut -d'"' -f2 | grep -v '^$' | sort -u ;;
  tap) adbs shell input tap "$2" "$3" ;;
  tapx) b=$(dump | grep -oE "<node[^>]*text=\"$2\"[^>]*>" | head -1 | grep -oE 'bounds="[^"]+"' | cut -d'"' -f2); [ -n "$b" ] || { echo "exact text not found: $2"; exit 1; }; adb -s "$SERIAL" shell input tap $(center "$b"); ;;
  tapt) b=$(find_bounds text "$2"); [ -n "$b" ] || { echo "text not found: $2"; exit 1; }; adbs shell input tap $(center "$b"); ;;
  tapd) b=$(find_bounds content-desc "$2"); [ -n "$b" ] || { echo "desc not found: $2"; exit 1; }; adbs shell input tap $(center "$b"); ;;
  type) adbs shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" ;;
  back) adbs shell input keyevent 4 ;;
  home) adbs shell input keyevent 3 ;;
  enter) adbs shell input keyevent 66 ;;
  swipe) case "$2" in up) adbs shell input swipe 540 1600 540 600 300;; down) adbs shell input swipe 540 600 540 1600 300;; left) adbs shell input swipe 900 1200 100 1200 300;; right) adbs shell input swipe 100 1200 900 1200 300;; esac ;;
  wait) t=0; until dump | grep -q "$2"; do sleep 1; t=$((t+1)); [ $t -ge "${3:-15}" ] && { echo "timeout waiting for: $2"; exit 1; }; done; echo "found: $2" ;;
  launch) adbs shell monkey -p com.myhealth -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2 ;;
  *) echo "usage: see header"; exit 2 ;;
esac

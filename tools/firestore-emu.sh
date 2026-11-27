#!/usr/bin/env bash
# P27.3: the Firebase Local Emulator Suite (Auth + Firestore) for the family-library tests.
#
#   tools/firestore-emu.sh test    run FirestoreRulesEmulatorTest against fresh emulators
#   tools/firestore-emu.sh serve   keep the emulators running on 0.0.0.0 (the Android emulator
#                                  reaches them at 10.0.2.2) for manual walkthroughs
#
# Project "demo-family": demo-* projects need no Google login and never touch the cloud.
# firebase-tools is pinned and installed outside the repo, in ~/.local/share/firebase-tools.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FT_DIR="$HOME/.local/share/firebase-tools"
FT_VERSION="15.36.0"
FIREBASE="$FT_DIR/node_modules/.bin/firebase"
export JAVA_HOME="${JAVA_HOME:-/home/robert/jdk/current}"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$FIREBASE" ] || [ "$("$FIREBASE" --version)" != "$FT_VERSION" ]; then
    npm install --prefix "$FT_DIR" "firebase-tools@$FT_VERSION" --no-fund --no-audit
fi

WORK="$ROOT/build/firestore-emu"
mkdir -p "$WORK"
# Open rules at start; the test loads the generated ones through the emulator's REST API.
cat > "$WORK/firestore.rules" <<'RULES'
rules_version = '2';
service cloud.firestore { match /databases/{db}/documents { match /{d=**} { allow read, write: if false; } } }
RULES
cat > "$WORK/firebase.json" <<'JSON'
{
  "firestore": { "rules": "firestore.rules" },
  "emulators": {
    "auth": { "host": "0.0.0.0", "port": 9099 },
    "firestore": { "host": "0.0.0.0", "port": 8080 },
    "ui": { "enabled": false },
    "singleProjectMode": true
  }
}
JSON
cd "$WORK"

case "${1:-test}" in
    test)
        "$FIREBASE" emulators:exec --only auth,firestore --project demo-family \
            "cd '$ROOT' && FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 \
             ./gradlew -q :app:testDebugUnitTest --tests 'com.myhealth.data.share.*EmulatorTest' --rerun"
        R="$ROOT/app/build/test-results/testDebugUnitTest/TEST-com.myhealth.data.share.*EmulatorTest.xml"
        cat $R | grep -o "<testsuite [^>]*" | sed 's/ timestamp.*//'
        if cat $R | grep -q "skipped=\"[1-9]"; then echo "FAIL: tests were skipped"; exit 1; fi
        ;;
    serve)
        "$FIREBASE" emulators:start --only auth,firestore --project demo-family
        ;;
    *)
        echo "usage: $0 [test|serve]"; exit 2 ;;
esac

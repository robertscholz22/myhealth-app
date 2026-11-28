#!/usr/bin/env bash
# P28: runs the AI relay locally (`wrangler dev`, port 8787) for emulator and script tests.
# The Android emulator reaches it at http://10.0.2.2:8787. Prints the matching connection code.
# Usage: tools/relay-dev.sh [serve|code]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/relay"
KEY=dev-registration-key-0123456789
[ -f .dev.vars ] || printf 'REGISTRATION_KEY=%s\n' "$KEY" > .dev.vars
[ -d node_modules ] || npm install --no-audit --no-fund
case "${1:-serve}" in
  code) node scripts/connection-code.mjs "${RELAY_URL:-http://10.0.2.2:8787}" "$KEY" ;;
  serve)
    echo "connection code (emulator): $(node scripts/connection-code.mjs http://10.0.2.2:8787 "$KEY")"
    exec npx wrangler dev --port 8787 --persist-to "$ROOT/build/relay-dev-state" ;;
  *) echo "Usage: tools/relay-dev.sh [serve|code]"; exit 1 ;;
esac

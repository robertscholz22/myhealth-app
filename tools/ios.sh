#!/usr/bin/env bash
# Runs the iOS workflow (P21, .github/workflows/ios.yml) on GitHub's macOS runners for a pushed
# ref and downloads its results (screenshots, accessibility trees, logs) to build/ios/<run id>/.
#   bash tools/ios.sh            # main
#   bash tools/ios.sh <branch>
set -euo pipefail
REPO=robertscholz22/myhealth-app
REF=${1:-main}
cd "$(dirname "$0")/.."

before=$(gh run list -R "$REPO" --workflow ios.yml --limit 1 --json databaseId --jq '.[0].databaseId // 0')
gh workflow run ios.yml -R "$REPO" --ref "$REF"
for _ in $(seq 1 30); do
  id=$(gh run list -R "$REPO" --workflow ios.yml --limit 1 --json databaseId --jq '.[0].databaseId // 0')
  [ "$id" != "$before" ] && break
  sleep 4
done
echo "run $id: https://github.com/$REPO/actions/runs/$id"
status=0
gh run watch "$id" -R "$REPO" --exit-status --interval 30 > /dev/null || status=$?
out="build/ios/$id"
mkdir -p "$out"
gh run download "$id" -R "$REPO" -D "$out" || true
echo "conclusion: $(gh run view "$id" -R "$REPO" --json conclusion --jq .conclusion)"
echo "results: $out"
exit $status

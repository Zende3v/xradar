#!/usr/bin/env bash
# Local CI for the iOS app (run on a Mac with Xcode 26).
# Regenerates the Xcode project, then runs every test on a simulator:
# XRadarKit package tests + app unit tests + UI tests.
#
#   scripts/ci.sh          all tests
#   scripts/ci.sh unit     skip UI tests
#   XR_DESTINATION='platform=iOS Simulator,name=iPhone 17 Pro' scripts/ci.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "xcodegen introuvable : brew install xcodegen" >&2
  exit 1
fi

xcodegen generate

DESTINATION="${XR_DESTINATION:-platform=iOS Simulator,name=iPhone 17,OS=latest}"
RESULTS="build/TestResults.xcresult"
rm -rf "$RESULTS"

EXTRA=()
if [[ "${1:-}" == "unit" ]]; then
  EXTRA+=("-skip-testing:XRadarUITests")
fi

xcodebuild test \
  -project XRadar.xcodeproj \
  -scheme XRadar \
  -destination "$DESTINATION" \
  -resultBundlePath "$RESULTS" \
  CODE_SIGNING_ALLOWED=NO \
  ${EXTRA[@]+"${EXTRA[@]}"} \
  | if command -v xcbeautify >/dev/null 2>&1; then xcbeautify; else cat; fi

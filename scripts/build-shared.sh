#!/usr/bin/env bash
# Assembles the ZenTaskShared XCFramework for the macOS app.
#   scripts/build-shared.sh [debug|release]   (default: release)
source "$(dirname "$0")/lib/env.sh"

CONFIG="${1:-release}"
case "$CONFIG" in
    debug)   TASK=":shared:assembleZenTaskSharedDebugXCFramework" ;;
    release) TASK=":shared:assembleZenTaskSharedReleaseXCFramework" ;;
    *) die "usage: scripts/build-shared.sh [debug|release]" ;;
esac

step "Building ZenTaskShared ($CONFIG)"
./gradlew -q "$TASK"

OUT="shared/build/XCFrameworks/$CONFIG/ZenTaskShared.xcframework"
[[ -d "$OUT" ]] || die "expected $OUT"
echo "    $OUT"

#!/usr/bin/env bash
# Builds the macOS app into build/ZenTask.app.
#   scripts/build-mac.sh [Debug|Release]            (default: Release)
#   SIGN_IDENTITY="ZenTask Self-Signed" scripts/build-mac.sh Release
source "$(dirname "$0")/lib/env.sh"

CONFIG="${1:-Release}"
[[ "$CONFIG" == "Debug" || "$CONFIG" == "Release" ]] || die "usage: scripts/build-mac.sh [Debug|Release]"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"

# The app always links the release XCFramework (see project.yml).
scripts/build-shared.sh release

step "Generating Xcode project"
(cd apps/macos && xcodegen generate -q)

step "Building ZenTask ($CONFIG, signed with '$SIGN_IDENTITY')"
xcodebuild -project apps/macos/ZenTask.xcodeproj -scheme ZenTask -configuration "$CONFIG" \
    -derivedDataPath build/DerivedData -destination 'generic/platform=macOS' \
    CODE_SIGN_IDENTITY="$SIGN_IDENTITY" OTHER_CODE_SIGN_FLAGS="--timestamp=none" \
    build -quiet

rm -rf build/ZenTask.app
ditto "build/DerivedData/Build/Products/$CONFIG/ZenTask.app" build/ZenTask.app
echo "    build/ZenTask.app"

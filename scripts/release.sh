#!/usr/bin/env bash
# scripts/release.sh — cut a ZenTask macOS release end to end.
#
#   scripts/release.sh 0.2.0                 # notes generated from commits
#   scripts/release.sh 0.2.0 notes.md        # notes from a Markdown file
#   DRY_RUN=1 scripts/release.sh 0.2.0       # build + verify only, then undo the bump
#   INSTALL=1 scripts/release.sh 0.2.0       # also replace /Applications/ZenTask.app
#   PUBLISH=0 scripts/release.sh 0.2.0       # commit + tag locally, no push / GitHub release
#   SKIP_TESTS=1 ...                         # skip Gradle + Xcode tests
#
# Steps: preflight → bump VERSION + project.yml → tests → build → sign → verify signer →
# zip → commit + tag → push main + GitHub release → install.
source "$(dirname "$0")/lib/env.sh"

REPO="vuhpquang/zentask"
BRANCH="main"
APP="build/ZenTask.app"
BUNDLE_ID="com.vuhpquang.zentask"
PROJECT_YML="apps/macos/project.yml"
SIGN_IDENTITY="ZenTask Self-Signed"
RELEASE_CERT="8ad13567dd279f4c0da4738aaa5fbc4eae22523c"   # SHA-1 of that certificate
DRY_RUN="${DRY_RUN:-0}"
PUBLISH="${PUBLISH:-1}"

VERSION="${1:-}"
NOTES_FILE="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh X.Y.Z [notes.md]"
[[ -z "$NOTES_FILE" || -f "$NOTES_FILE" ]] || die "notes file not found: $NOTES_FILE"
TAG="v${VERSION}"
ZIP="build/ZenTask-${VERSION}.zip"

step "Preflight"
[[ "$(git branch --show-current)" == "$BRANCH" ]] || die "release from '$BRANCH'"
[[ -z "$(git status --porcelain)" ]] || die "working tree not clean"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"
if [[ "$PUBLISH" == "1" && "$DRY_RUN" != "1" ]]; then
    gh auth status >/dev/null 2>&1 || die "gh is not logged in"
    # A push rejected after tagging would strand the local tag, so require main == origin/main up front.
    git fetch -q origin "$BRANCH" --tags
    [[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/$BRANCH")" ]] \
        || die "$BRANCH is not in sync with origin/$BRANCH (pull or push first)"
fi
security find-identity -p codesigning | grep -q "\"$SIGN_IDENTITY\"" \
    || die "signing identity '$SIGN_IDENTITY' not in keychain (import ~/.config/zentask-signing/zentask-signing.p12)"

CURRENT="$(tr -d '[:space:]' < VERSION)"
BUILD="$(sed -n 's/^ *CURRENT_PROJECT_VERSION: *//p' "$PROJECT_YML" | head -1)"
newest="$(printf '%s\n%s\n' "$CURRENT" "$VERSION" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
[[ "$VERSION" != "$CURRENT" && "$newest" == "$VERSION" ]] || die "$VERSION must be newer than $CURRENT"
echo "    $CURRENT (build $BUILD) → $VERSION (build $((BUILD + 1)))"

COMMITTED=0
undo() { if [[ "$DRY_RUN" == "1" || "$COMMITTED" == "0" ]]; then git checkout -q -- VERSION "$PROJECT_YML"; fi; }
trap undo EXIT

step "Bumping version"
echo "$VERSION" > VERSION
sed -i '' -e "s/^\( *MARKETING_VERSION:\).*/\1 $VERSION/" \
          -e "s/^\( *CURRENT_PROJECT_VERSION:\).*/\1 $((BUILD + 1))/" "$PROJECT_YML"

if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
    step "Running tests"
    ./gradlew -q :shared:allTests :server:test || die "Gradle tests failed"
    scripts/build-shared.sh release >/dev/null
    git submodule update --init --recursive   # Vendor/app-shell (shared updater / About / Settings), pinned by commit
    (cd apps/macos && xcodegen generate -q)
    xcodebuild test -project apps/macos/ZenTask.xcodeproj -scheme ZenTask \
        -destination 'platform=macOS,arch=arm64' -derivedDataPath build/DerivedData -quiet \
        || die "Xcode tests failed"
fi

step "Building"
SIGN_IDENTITY="$SIGN_IDENTITY" scripts/build-mac.sh Release
# The widget extension must keep its sandbox entitlements (--deep would drop them): sign it first, then the app.
for ext in "$APP"/Contents/PlugIns/*.appex; do
    [[ -e "$ext" ]] || continue
    codesign --force --options runtime --timestamp=none --sign "$SIGN_IDENTITY" \
        --entitlements apps/macos/ZenTaskWidget/ZenTaskWidget.entitlements "$ext"
done
codesign --force --options runtime --timestamp=none --sign "$SIGN_IDENTITY" "$APP"

step "Verifying the release"
codesign --verify --deep --strict "$APP"
for ext in "$APP"/Contents/PlugIns/*.appex; do
    [[ -e "$ext" ]] || continue
    codesign -d --entitlements - "$ext" 2>&1 | grep -q "com.apple.security.app-sandbox" || die "$(basename "$ext") lost its sandbox entitlement"
done
built="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
[[ "$built" == "$VERSION" ]] || die "bundle says $built, expected $VERSION"
codesign -d -r- "$APP" 2>&1 | grep -qiE "certificate leaf = H\"$RELEASE_CERT\"" \
    || die "ZenTask.app is not signed by the release certificate"
[[ "$(lipo -archs "$APP/Contents/MacOS/ZenTask")" == *arm64* ]] || die "missing arm64 slice"
rm -f "$ZIP"
ditto -c -k --keepParent "$APP" "$ZIP"
echo "    signed by release certificate, arm64, $ZIP sha256 $(shasum -a 256 "$ZIP" | awk '{print $1}')"

# Android phone + Wear OS APKs ride along on the same GitHub release (download on the phone to install,
# setup-guide §7–8). Only where an Android SDK is installed; the Mac release doesn't depend on it.
APKS=()
if [[ -n "${ANDROID_HOME:-}" || -f local.properties ]]; then
    step "Building Android APKs"
    ./gradlew -q :android:assembleRelease :wear:assembleRelease || die "Android build failed"
    cp apps/android/build/outputs/apk/release/android-release.apk "build/ZenTask-$VERSION-android.apk"
    cp apps/wear/build/outputs/apk/release/wear-release.apk "build/ZenTask-$VERSION-wear.apk"
    APKS=("build/ZenTask-$VERSION-android.apk" "build/ZenTask-$VERSION-wear.apk")
fi

if [[ "$DRY_RUN" == "1" ]]; then
    step "Dry run — stopping before commit; version bump reverted. Artifacts are in build/."
    exit 0
fi

step "Committing and tagging $TAG"
git add VERSION "$PROJECT_YML"
git commit -q -m "release: $TAG"
COMMITTED=1
git tag -a "$TAG" -m "ZenTask $VERSION"

if [[ "$PUBLISH" == "1" ]]; then
    step "Pushing and creating GitHub release $TAG"
    git push -q origin "$BRANCH" "$TAG"
    if [[ -n "$NOTES_FILE" ]]; then
        gh release create "$TAG" "$ZIP" ${APKS[@]+"${APKS[@]}"} -R "$REPO" --title "ZenTask $VERSION" --notes-file "$NOTES_FILE" --latest
    else
        gh release create "$TAG" "$ZIP" ${APKS[@]+"${APKS[@]}"} -R "$REPO" --title "ZenTask $VERSION" --generate-notes --latest
    fi
fi

if [[ "${INSTALL:-0}" == "1" ]]; then
    step "Installing to /Applications"
    # Match the installed copy by path: a Debug build (.dev bundle id) has the same process name.
    INSTALLED_BIN="/Applications/ZenTask.app/Contents/MacOS/ZenTask"
    osascript -e "quit app id \"$BUNDLE_ID\"" >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do pgrep -fq "$INSTALLED_BIN" || break; sleep 0.5; done
    pgrep -fq "$INSTALLED_BIN" && die "installed ZenTask is still running; quit it and run: ditto $APP /Applications/ZenTask.app"
    rm -rf /Applications/ZenTask.app
    ditto "$APP" /Applications/ZenTask.app
    # ditto updates files inside an existing bundle but not the bundle's own date, which macOS uses to
    # notice a new icon; touch it and re-register so Finder/LaunchServices pick up icon changes.
    touch /Applications/ZenTask.app
    /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f /Applications/ZenTask.app || true
    xattr -dr com.apple.quarantine /Applications/ZenTask.app 2>/dev/null || true
    open -a /Applications/ZenTask.app
fi

step "Released ZenTask $VERSION"
[[ "$PUBLISH" == "1" ]] && echo "    https://github.com/$REPO/releases/tag/$TAG"
exit 0

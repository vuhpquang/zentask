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
    (cd apps/macos && xcodegen generate -q)
    xcodebuild test -project apps/macos/ZenTask.xcodeproj -scheme ZenTask \
        -destination 'platform=macOS,arch=arm64' -derivedDataPath build/DerivedData -quiet \
        || die "Xcode tests failed"
fi

step "Building"
SIGN_IDENTITY="$SIGN_IDENTITY" scripts/build-mac.sh Release
codesign --force --deep --options runtime --timestamp=none --sign "$SIGN_IDENTITY" "$APP"

step "Verifying the release"
codesign --verify --deep --strict "$APP"
built="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
[[ "$built" == "$VERSION" ]] || die "bundle says $built, expected $VERSION"
codesign -d -r- "$APP" 2>&1 | grep -qiE "certificate leaf = H\"$RELEASE_CERT\"" \
    || die "ZenTask.app is not signed by the release certificate"
[[ "$(lipo -archs "$APP/Contents/MacOS/ZenTask")" == *arm64* ]] || die "missing arm64 slice"
rm -f "$ZIP"
ditto -c -k --keepParent "$APP" "$ZIP"
echo "    signed by release certificate, arm64, $ZIP sha256 $(shasum -a 256 "$ZIP" | awk '{print $1}')"

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
        gh release create "$TAG" "$ZIP" -R "$REPO" --title "ZenTask $VERSION" --notes-file "$NOTES_FILE" --latest
    else
        gh release create "$TAG" "$ZIP" -R "$REPO" --title "ZenTask $VERSION" --generate-notes --latest
    fi
fi

if [[ "${INSTALL:-0}" == "1" ]]; then
    step "Installing to /Applications"
    osascript -e "quit app id \"$BUNDLE_ID\"" >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do pgrep -xq ZenTask || break; sleep 0.5; done
    pgrep -xq ZenTask && die "ZenTask is still running; quit it and run: ditto $APP /Applications/ZenTask.app"
    rm -rf /Applications/ZenTask.app
    ditto "$APP" /Applications/ZenTask.app
    xattr -dr com.apple.quarantine /Applications/ZenTask.app 2>/dev/null || true
    open -a /Applications/ZenTask.app
fi

step "Released ZenTask $VERSION"
[[ "$PUBLISH" == "1" ]] && echo "    https://github.com/$REPO/releases/tag/$TAG"
exit 0

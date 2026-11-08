# ZenTask P0 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A buildable KMP monorepo where an empty SwiftUI macOS app (installed in `/Applications` by `scripts/release.sh`) shows a version string that comes from the Kotlin `shared` module, and a Ktor server answering `/health` that deploys to `vps1` with `scripts/deploy.sh`.

**Architecture:** One Gradle build with two modules: `shared` (Kotlin Multiplatform, targets `jvm` + `macosArm64`, exported to Swift as the XCFramework `ZenTaskShared` with SKIE) and `server` (Ktor/JVM, depends on `shared`). The macOS app is an xcodegen project in `apps/macos` that embeds the XCFramework. A root `VERSION` file is the single source of the app version.

**Tech Stack:** Kotlin Multiplatform, SKIE, Ktor 3 (server, Netty), kotlinx.serialization, Gradle wrapper + version catalog, SwiftUI (macOS 26+, arm64), xcodegen, Docker Compose, Cloudflare Tunnel (existing).

**Spec:** `docs/superpowers/specs/2026-11-08-zentask-design.md` (sections 5, 8, 12, 13-P0). Manual steps the owner does: `docs/setup-guide.md`.

This is the first of nine phase plans (P0–P8). Each later phase gets its own plan written when the previous one ships, so it can build on real code.

## Global Constraints

- Repo root: `projects/zentask` inside the software-team hub; GitHub `vuhpquang/zentask`, branch `main`.
- Kotlin package root: `com.vuhpquang.zentask`. Swift module of the shared framework: `ZenTaskShared`.
- Bundle id `com.vuhpquang.zentask`; Debug build uses `com.vuhpquang.zentask.dev`. App name `ZenTask`. Target macOS 26.0, arm64 only.
- JDK: arm64 JDK 17. On this Mac that is Corretto 17 (`/usr/libexec/java_home -v 17` resolves it). The Oracle JDK 18 on the PATH is x86_64 and fails with "Bad CPU type". Every script exports `JAVA_HOME="$(/usr/libexec/java_home -v 17)"` before calling Gradle. Gradle toolchains pin `jvmToolchain(17)`.
- Kotlin version = the newest Kotlin that the newest SKIE release supports (check SKIE's compatibility table when starting Task 1). Pin it in `gradle/libs.versions.toml`. Xcode 27.1 is newer than Kotlin/Native officially tests against: set `kotlin.apple.xcodeCompatibility.nowarn=true` in `gradle.properties`.
- Server listens on port `3100`. Docker publishes `127.0.0.1:3100:3100` only. Public URL `https://zentask-api.vuhpquang.com` through the existing Cloudflare Tunnel on vps1.
- Server secrets come only from `/root/zentask/.env` on vps1 (never committed): `ZENTASK_PASSWORD`, `ZENTASK_ENCRYPTION_KEY`, `ZENTASK_PUBLIC_URL`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`. P0 reads none of them, but the compose file already loads `.env`.
- Signing: self-signed identity named exactly `ZenTask Self-Signed`, backed up in `~/.config/zentask-signing/` (`cert.cnf`, `cert.pem`, `zentask-signing.p12`, `p12-password.txt`), same recipe as `~/.config/launchapp-signing/`.
- No ZenTask or TickTick branding assets copied from TickTick. Placeholder app icon is fine for P0.
- Commits in this repo: `type(scope): message` (no Jira key). End every message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not `git push` unless the owner allows it (see Task 6).
- Do not run `npx gitnexus analyze` inside this repo.

## Review Focus

1. **Wrong JDK picked up**: if a shell has `JAVA_HOME` unset or pointing at JDK 18 x86_64, every script must still work. → Task 1 adds `scripts/lib/env.sh`; Task 1 verification runs Gradle with `JAVA_HOME` deliberately pointed at the x86 JDK and expects success.
2. **App and shared versions drift**: a release that bumps the Xcode version but not the Kotlin constant would make the app show two different versions. → Task 3 test `testBundleVersionMatchesSharedVersion`.
3. **Release run on a dirty tree, or replacing a running app**: the release must refuse to run on uncommitted changes and must quit the running ZenTask before replacing `/Applications/ZenTask.app`. → Task 6 dry-run checks.
4. **Server bound to the public interface**: compose must never publish `0.0.0.0:3100`. → Task 5 verification greps the compose file.
5. **Health check hit with an unknown path or method**: should return 404, not 500 or a stack trace. → Task 4 test `unknownRouteReturns404`.

---

## File map

```
zentask/
├── VERSION                         # "0.1.0" — single source of the app version
├── settings.gradle.kts             # includes :shared, :server; foojay toolchain resolver
├── build.gradle.kts                # root: plugin declarations only (apply false)
├── gradle.properties               # kotlin/native flags, jvm args
├── gradle/libs.versions.toml       # all versions
├── gradlew, gradlew.bat, gradle/wrapper/*
├── shared/
│   ├── build.gradle.kts            # KMP jvm + macosArm64, XCFramework "ZenTaskShared", SKIE, generateBuildInfo task
│   └── src/
│       ├── commonMain/kotlin/com/vuhpquang/zentask/ZenTaskInfo.kt
│       └── commonTest/kotlin/com/vuhpquang/zentask/ZenTaskInfoTest.kt
├── server/
│   ├── build.gradle.kts            # Ktor, application plugin, installDist
│   ├── Dockerfile
│   └── src/
│       ├── main/kotlin/com/vuhpquang/zentask/server/Application.kt
│       └── test/kotlin/com/vuhpquang/zentask/server/HealthRouteTest.kt
├── apps/macos/
│   ├── project.yml                 # xcodegen; MARKETING_VERSION written by release.sh
│   ├── ZenTask/ZenTaskApp.swift
│   ├── ZenTask/AppInfo.swift
│   ├── ZenTask/RootView.swift
│   ├── ZenTask/Assets.xcassets     # placeholder AppIcon + AccentColor
│   └── ZenTaskTests/AppInfoTests.swift
├── docker-compose.yml
├── scripts/
│   ├── lib/env.sh                  # JAVA_HOME + common helpers (die, step)
│   ├── build-shared.sh             # → shared/build/XCFrameworks/release/ZenTaskShared.xcframework
│   ├── build-mac.sh                # xcodegen + xcodebuild Release → build/ZenTask.app
│   ├── make-signing-identity.sh    # one-time: create "ZenTask Self-Signed"
│   ├── release.sh
│   └── deploy.sh
├── .gitignore
├── README.md
└── AGENTS.md (+ CLAUDE.md pointer)
```

---

### Task 1: Gradle build + `shared` module with build info

**Files:**
- Create: `VERSION`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper files, `shared/build.gradle.kts`, `shared/src/commonMain/kotlin/com/vuhpquang/zentask/ZenTaskInfo.kt`, `shared/src/commonTest/kotlin/com/vuhpquang/zentask/ZenTaskInfoTest.kt`, `scripts/lib/env.sh`, `.gitignore`
- Test: `shared/src/commonTest/kotlin/com/vuhpquang/zentask/ZenTaskInfoTest.kt`

**Interfaces:**
- Produces: `object ZenTaskInfo { val version: String; val appName: String /* "ZenTask" */ }` in `com.vuhpquang.zentask`. `version` reads a generated constant `ZenTaskBuild.VERSION` (internal, generated into `build/generated/buildinfo/commonMain/kotlin` by Gradle task `generateBuildInfo` from the root `VERSION` file, trimmed).
- Produces: `scripts/lib/env.sh`, sourced by every script. It does `set -euo pipefail`, exports `JAVA_HOME="$(/usr/libexec/java_home -v 17)"`, `cd`s to the repo root, defines `die()` and `step()` (same as LaunchApp's release.sh).

- [ ] **Step 1: Write the failing test**

```kotlin
class ZenTaskInfoTest {
    @Test fun versionMatchesRootVersionFile() {
        // The test runs with working dir = shared/, so VERSION is one level up.
        // Only the jvm test can read files; declare this test in jvmTest instead if commonTest cannot.
        assertEquals("0.1.0", ZenTaskInfo.version)
    }
    @Test fun appNameIsZenTask() = assertEquals("ZenTask", ZenTaskInfo.appName)
}
```

Put both in `commonTest` (the version assertion uses the literal `"0.1.0"`, the value of `VERSION` in P0), so they run on `jvm` and `macosArm64`.

- [ ] **Step 2: Run test to verify it fails**

Run: `source scripts/lib/env.sh && ./gradlew :shared:allTests`
Expected: FAIL to compile with `Unresolved reference 'ZenTaskInfo'`.

- [ ] **Step 3: Implement**

`VERSION` contains `0.1.0`. `shared/build.gradle.kts`: KMP plugin, targets `jvm()` and `macosArm64()` with `binaries.framework { baseName = "ZenTaskShared"; isStatic = true }` registered through `XCFramework("ZenTaskShared")`, SKIE plugin applied, `jvmToolchain(17)`, and the `generateBuildInfo` task wired as a source dir of `commonMain` (`kotlin.srcDir(generateBuildInfo)`). `settings.gradle.kts` applies `org.gradle.toolchains.foojay-resolver-convention`. `gradle.properties`: `kotlin.apple.xcodeCompatibility.nowarn=true`, `org.gradle.jvmargs=-Xmx3g`, `kotlin.code.style=official`. `.gitignore`: `.gradle/`, `build/`, `*.xcodeproj` (generated by xcodegen), `xcuserdata/`, `.idea/`, `.DS_Store`, `local.properties`, `.env`, `data/`.

- [ ] **Step 4: Run test to verify it passes**

Run: `source scripts/lib/env.sh && ./gradlew :shared:allTests`
Expected: `BUILD SUCCESSFUL`, `jvmTest` and `macosArm64Test` both ran 2 tests.

- [ ] **Step 5: Verify the JDK guard (Review Focus 1)**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-18.0.2.jdk/Contents/Home bash -c 'source scripts/lib/env.sh && ./gradlew -q :shared:jvmTest && echo OK'`
Expected: `OK` (env.sh overrides the bad JAVA_HOME).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "build: Gradle KMP build with shared module and build info"
```

---

### Task 2: XCFramework build script

**Files:**
- Create: `scripts/build-shared.sh`

**Interfaces:**
- Consumes: `ZenTaskInfo` (Task 1).
- Produces: `scripts/build-shared.sh [debug|release]` (default `release`) → `shared/build/XCFrameworks/<config>/ZenTaskShared.xcframework` containing a `macos-arm64` slice. Swift sees `ZenTaskInfo.shared.version` (SKIE keeps the Kotlin object as `ZenTaskInfo.shared`).

- [ ] **Step 1: Implement** the script: source `scripts/lib/env.sh`, run `./gradlew :shared:assembleZenTaskShared<Release|Debug>XCFramework`, then check the output dir exists or `die`.

- [ ] **Step 2: Verify**

Run: `scripts/build-shared.sh && ls shared/build/XCFrameworks/release/ZenTaskShared.xcframework`
Expected: `Info.plist` and a `macos-arm64` directory.

Run: `grep -rq "ZenTaskInfo" shared/build/XCFrameworks/release/ZenTaskShared.xcframework/macos-arm64/ZenTaskShared.framework/Headers && echo OK`
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add scripts/build-shared.sh && git commit -m "build: script to assemble ZenTaskShared XCFramework"
```

---

### Task 3: macOS app shell (xcodegen) showing the shared version

**Files:**
- Create: `apps/macos/project.yml`, `apps/macos/ZenTask/ZenTaskApp.swift`, `apps/macos/ZenTask/AppInfo.swift`, `apps/macos/ZenTask/RootView.swift`, `apps/macos/ZenTask/Assets.xcassets/**`, `apps/macos/ZenTaskTests/AppInfoTests.swift`, `scripts/build-mac.sh`

**Interfaces:**
- Consumes: XCFramework from Task 2 (`import ZenTaskShared`, `ZenTaskInfo.shared.version`, `ZenTaskInfo.shared.appName`).
- Produces: `enum AppInfo { static var sharedVersion: String; static var bundleVersion: String; static var displayVersion: String }`. `displayVersion` is `"ZenTask 0.1.0"` when both match, and `"ZenTask app 0.1.0 / core 0.2.0"` when they differ, so drift is visible.
- Produces: `scripts/build-mac.sh [Debug|Release]` → runs `build-shared.sh` with the matching config, `xcodegen generate` in `apps/macos`, `xcodebuild -scheme ZenTask -configuration <c> -derivedDataPath build/DerivedData ARCHS=arm64`, and copies the product to `build/ZenTask.app`. Signing identity comes from env `SIGN_IDENTITY`, default `-` (ad-hoc).
- `project.yml`: target `ZenTask` (application, macOS 26.0), `PRODUCT_BUNDLE_IDENTIFIER` `com.vuhpquang.zentask` (Debug: `.dev`), `MARKETING_VERSION: 0.1.0`, `CURRENT_PROJECT_VERSION: 1`, framework dependency on `../../shared/build/XCFrameworks/$(CONFIGURATION:lower)/ZenTaskShared.xcframework` (fall back to the `release` path for both configs if xcodegen cannot expand the setting), embed not needed (static). Test target `ZenTaskTests` (unit tests, host app ZenTask).

- [ ] **Step 1: Write the failing test** in `apps/macos/ZenTaskTests/AppInfoTests.swift`

```swift
final class AppInfoTests: XCTestCase {
    func testBundleVersionMatchesSharedVersion() {
        XCTAssertEqual(AppInfo.bundleVersion, AppInfo.sharedVersion)
    }
    func testDisplayVersion() {
        XCTAssertEqual(AppInfo.displayVersion, "ZenTask 0.1.0")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scripts/build-shared.sh debug && cd apps/macos && xcodegen generate && xcodebuild test -scheme ZenTask -destination 'platform=macOS,arch=arm64' CODE_SIGN_IDENTITY=- 2>&1 | tail -5`
Expected: FAIL with `cannot find 'AppInfo' in scope`.

- [ ] **Step 3: Implement** `AppInfo.swift`, `ZenTaskApp.swift` (single `WindowGroup` with `RootView`, default size 1100×720, dark-mode-first via system appearance), `RootView.swift` (centered app name + `AppInfo.displayVersion`, nothing else), placeholder icon/accent assets, and `scripts/build-mac.sh`.

- [ ] **Step 4: Run test to verify it passes**

Run: same command as Step 2.
Expected: `** TEST SUCCEEDED **`, 2 tests.

- [ ] **Step 5: Verify the build script**

Run: `scripts/build-mac.sh Release && /usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' build/ZenTask.app/Contents/Info.plist && lipo -archs build/ZenTask.app/Contents/MacOS/ZenTask`
Expected: `0.1.0` and `arm64`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(macos): app shell showing version from shared core"
```

---

### Task 4: Ktor server with `/health`

**Files:**
- Create: `server/build.gradle.kts`, `server/src/main/kotlin/com/vuhpquang/zentask/server/Application.kt`, `server/src/test/kotlin/com/vuhpquang/zentask/server/HealthRouteTest.kt`
- Modify: `settings.gradle.kts` (include `:server`), `gradle/libs.versions.toml` (ktor, logback)

**Interfaces:**
- Consumes: `ZenTaskInfo.version` (shared, jvm target).
- Produces: `fun Application.module()` (installs ContentNegotiation with kotlinx.serialization JSON, StatusPages mapping uncaught exceptions to `500 {"error":"internal"}` with no stack trace in the body, and routing). `fun main()` starts Netty on `System.getenv("PORT")?.toInt() ?: 3100`, host `0.0.0.0` (inside the container only). `@Serializable data class HealthResponse(val status: String, val version: String)`. `GET /health` → `200 {"status":"ok","version":"0.1.0"}`. Later phases add routes under `/v1`.
- Gradle: `application { mainClass = "com.vuhpquang.zentask.server.ApplicationKt" }`, `installDist` produces `server/build/install/server`.

- [ ] **Step 1: Write the failing tests** with Ktor `testApplication { application { module() } }`

```kotlin
@Test fun healthReturnsOkAndVersion() = testApplication {
    application { module() }
    val res = client.get("/health")
    assertEquals(HttpStatusCode.OK, res.status)
    assertEquals("""{"status":"ok","version":"0.1.0"}""", res.bodyAsText())
}
@Test fun unknownRouteReturns404() = testApplication {
    application { module() }
    assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    assertEquals(HttpStatusCode.NotFound, client.post("/health").status.let { if (it == HttpStatusCode.MethodNotAllowed) HttpStatusCode.NotFound else it })
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `source scripts/lib/env.sh && ./gradlew :server:test`
Expected: FAIL, `Unresolved reference 'module'`.

- [ ] **Step 3: Implement** `server/build.gradle.kts` and `Application.kt` per Interfaces.

- [ ] **Step 4: Run to verify they pass**

Run: `source scripts/lib/env.sh && ./gradlew :server:test`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Run it locally**

Run: `source scripts/lib/env.sh && ./gradlew -q :server:installDist && (server/build/install/server/bin/server & echo $! > /tmp/zt.pid; sleep 4; curl -s localhost:3100/health; kill $(cat /tmp/zt.pid))`
Expected: `{"status":"ok","version":"0.1.0"}`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(server): Ktor server with health endpoint"
```

---

### Task 5: Docker image, compose, deploy to vps1

**Files:**
- Create: `server/Dockerfile`, `docker-compose.yml`, `scripts/deploy.sh`, `.dockerignore`

**Interfaces:**
- Consumes: `:server:installDist` (Task 4).
- Produces: `scripts/deploy.sh` — `REMOTE=vps1`, `REMOTE_DIR=/root/zentask`; `ssh mkdir -p`, `rsync -az --delete` the repo excluding `.git`, `build`, `.gradle`, `apps`, `data`, `.env`, `**/build`; then `ssh "cd /root/zentask && docker compose up -d --build"`; then poll `curl -fsS https://zentask-api.vuhpquang.com/health` up to 60 s and print it, or print the local check `ssh vps1 curl -fsS localhost:3100/health` and the hint "DNS/tunnel not set up yet — see docs/setup-guide.md §1" if the public URL fails but the local one works.
- Dockerfile: multi-stage. Stage 1 `eclipse-temurin:17-jdk` runs `./gradlew :server:installDist --no-daemon` (copy only Gradle files + `VERSION` + `shared/` + `server/`). Stage 2 `eclipse-temurin:17-jre`, copies `server/build/install/server`, `EXPOSE 3100`, `ENV PORT=3100 DATA_DIR=/app/data`, `CMD ["/app/server/bin/server"]`. The build stage must not compile Kotlin/Native: guard the `macosArm64` target in `shared/build.gradle.kts` with `if (HostManager.hostIsMac)` so Linux builds only configure `jvm`.
- `docker-compose.yml`: service `api`, `build: {context: ., dockerfile: server/Dockerfile}`, `restart: unless-stopped`, `env_file: [{path: .env, required: false}]`, `volumes: [zentask_data:/app/data]`, `ports: ["127.0.0.1:3100:3100"]`; volume `zentask_data`.

- [ ] **Step 1: Implement** the four files and the `HostManager.hostIsMac` guard.

- [ ] **Step 2: Verify the image builds and runs locally** (Docker Desktop or colima must be running; if neither is available, skip to Step 4 and let vps1 build it)

Run: `docker compose build && docker compose up -d && sleep 5 && curl -s localhost:3100/health; docker compose down`
Expected: `{"status":"ok","version":"0.1.0"}`.

- [ ] **Step 3: Verify the port binding (Review Focus 4)**

Run: `grep -E '^\s*-\s*"?127\.0\.0\.1:3100:3100"?' docker-compose.yml && ! grep -qE '"?(0\.0\.0\.0:)?3100:3100"?$' <(grep -v 127.0.0.1 docker-compose.yml) && echo OK`
Expected: `OK`.

- [ ] **Step 4: Deploy** — only if the owner has finished `docs/setup-guide.md` §1 or agrees to a local-only check.

Run: `scripts/deploy.sh`
Expected: final line prints `{"status":"ok","version":"0.1.0"}` from the public URL (or the local-on-vps1 fallback plus the DNS hint).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "build(server): Docker image, compose and deploy script for vps1"
```

---

### Task 6: Signing identity + `scripts/release.sh` installing to /Applications

**Files:**
- Create: `scripts/make-signing-identity.sh`, `scripts/release.sh`

**Interfaces:**
- Consumes: `scripts/build-mac.sh` (Task 3), `scripts/lib/env.sh` (Task 1).
- Produces: `scripts/make-signing-identity.sh` — one-time. Refuses if `security find-identity -p codesigning` already lists `ZenTask Self-Signed`. Otherwise writes `~/.config/zentask-signing/cert.cnf` (same content as LaunchApp's with `CN = ZenTask Self-Signed`), generates key + cert (`openssl req -x509 -newkey rsa:2048 -days 3650 -nodes`), packs a `.p12` with a random password saved to `p12-password.txt` (use `-legacy` if the installed openssl is 3.x so `security import` accepts it), imports into the login keychain with `-T /usr/bin/codesign`, trusts it for code signing (`security add-trusted-cert -p codeSign`, prompts for the owner's password — tell the owner before running), and prints the cert SHA-1.
- Produces: `scripts/release.sh X.Y.Z [notes.md]`, modelled on `projects/launch-app/scripts/release.sh`:
  - Env: `DRY_RUN=1` (build + verify, revert bump), `INSTALL=1` (replace `/Applications/ZenTask.app`), `PUBLISH=1` (push `main` + tag and `gh release create` with the zip; **off by default** until the owner allows pushing this repo).
  - Preflight: branch `main`, clean tree, tag absent, version newer than `VERSION`, identity `ZenTask Self-Signed` present.
  - Bump: write `VERSION`, `MARKETING_VERSION` in `apps/macos/project.yml`, increment `CURRENT_PROJECT_VERSION`.
  - Test: `./gradlew :shared:allTests :server:test` and `xcodebuild test` (as Task 3). `SKIP_TESTS=1` skips.
  - Build: `SIGN_IDENTITY="ZenTask Self-Signed" scripts/build-mac.sh Release`, then `codesign --force --deep --options runtime --sign "ZenTask Self-Signed" build/ZenTask.app` if build-mac did not sign.
  - Verify: `codesign --verify --deep --strict`, bundle version == X.Y.Z, signer leaf is the ZenTask cert (`codesign -d -r-` contains the SHA-1 stored in `RELEASE_CERT`, filled in after running make-signing-identity), `lipo -archs` contains `arm64`. Zip with `ditto -c -k --keepParent build/ZenTask.app build/ZenTask-X.Y.Z.zip`.
  - Commit `release: vX.Y.Z` + annotated tag (local). Publish only if `PUBLISH=1`.
  - Install if `INSTALL=1`: `osascript -e 'quit app id "com.vuhpquang.zentask"'`, wait until no process `ZenTask` (max 10 s, else `die`), `rm -rf /Applications/ZenTask.app`, `ditto`, remove quarantine, `open -a /Applications/ZenTask.app`.

- [ ] **Step 1: Create the identity** — tell the owner a keychain password prompt will appear, then run `scripts/make-signing-identity.sh`. Put the printed SHA-1 into `RELEASE_CERT` in `release.sh`.

- [ ] **Step 2: Verify the identity**

Run: `security find-identity -p codesigning | grep "ZenTask Self-Signed"`
Expected: one line with the same SHA-1.

- [ ] **Step 3: Verify the dirty-tree guard (Review Focus 3)**

Run: `touch scratch.tmp && scripts/release.sh 0.1.1; echo "exit=$?"; rm scratch.tmp`
Expected: `error: working tree not clean`, `exit=1`.

- [ ] **Step 4: Dry run**

Run: `DRY_RUN=1 scripts/release.sh 0.1.1 && git status --porcelain`
Expected: ends with "Dry run"; `git status` prints nothing (bump reverted); `build/ZenTask-0.1.1.zip` exists.

- [ ] **Step 5: Make version tests release-proof** (before any real release, or every bump breaks them)

`ZenTaskInfoTest.versionMatchesRootVersionFile` moves to `jvmTest` and compares with `File("../VERSION").readText().trim()`. `AppInfoTests.testDisplayVersion` asserts `"ZenTask \(AppInfo.sharedVersion)"`. Run both suites; expect them to pass.

- [ ] **Step 6: Real release + install**

Run: `INSTALL=1 scripts/release.sh 0.1.1` (`VERSION` stays `0.1.0` from Task 1, so the first release is 0.1.1).
Expected: `/Applications/ZenTask.app` opens showing `ZenTask 0.1.1`; `git tag` lists `v0.1.1`; `git log -1` is `release: v0.1.1`.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "build(release): signing identity and release script installing to /Applications"
```

---

### Task 7: Docs, hub registration

**Files:**
- Create: `README.md`, `AGENTS.md`, `CLAUDE.md` (one-line pointer to AGENTS.md, as in personal-wiki)
- Modify (hub repo `software-team`): `.gitmodules`, `CLAUDE.md` project table row for ZenTask (drop "not a submodule yet", set release command)

**Interfaces:**
- `AGENTS.md` covers: layout (file map above), JDK rule, build/test commands (`./gradlew :shared:allTests :server:test`, `scripts/build-mac.sh`, `xcodebuild test …`), release (`INSTALL=1 scripts/release.sh X.Y.Z`, `PUBLISH=1`), deploy (`scripts/deploy.sh`), signing backup location, "no gitnexus inside this repo", link to spec, setup guide, and plans.

- [ ] **Step 1: Write the docs.**

- [ ] **Step 2: Register the submodule in the hub** (from the hub root): `git submodule add -f https://github.com/vuhpquang/zentask.git projects/zentask` (the existing clone is reused), update the hub `CLAUDE.md` row, then `git add -f projects/zentask .gitmodules CLAUDE.md`. If git refuses because the remote has no commits yet, skip registration and note it in the report; it can be done after the first push.

- [ ] **Step 3: Verify**

Run: `cd projects/zentask && ./gradlew -q :shared:allTests :server:test && echo GRADLE_OK`
Expected: `GRADLE_OK`.

- [ ] **Step 4: Commit** in zentask: `docs: README and agent guide`; in the hub: `chore: add zentask submodule`, then run `npx gitnexus analyze` in the hub root.

---

## Done when (spec §13 P0)

- `/Applications/ZenTask.app` opens and shows `ZenTask <version>`, the version coming from Kotlin `shared`.
- `./gradlew :shared:allTests :server:test` and `xcodebuild test` pass.
- `scripts/deploy.sh` brings up the server on vps1, and `/health` answers (publicly if the owner finished setup-guide §1).

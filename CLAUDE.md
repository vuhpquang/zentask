# ZenTask — notes for Claude

ZenTask is the owner's personal task manager, a TickTick replacement with its own name and look
(blue→violet ensō logo, accent #5B5FE9 light / #8B8CFF dark — never TickTick's branding). Kotlin Multiplatform core + SwiftUI macOS app + Ktor
server. Repo `vuhpquang/zentask`, branch `main` (also the release branch — commit and push straight
to it, no need to ask). Bundle id `com.vuhpquang.zentask` (`.dev` for Debug), macOS 26+, arm64.
Server: `zentask-api.vuhpquang.com` on vps1 (Docker, Cloudflare Tunnel → `127.0.0.1:3100`).

Read first: `docs/superpowers/specs/2026-11-08-zentask-design.md` (the spec), the current phase plan
in `docs/superpowers/plans/`, and `docs/setup-guide.md` (steps only the owner can do).
Platforms come in order macOS → Android → Windows → Galaxy Watch; Android started in P7.

## Commands

```bash
source scripts/lib/env.sh                       # JDK 17 arm64 + repo root (scripts do this themselves)
./gradlew :shared:allTests :server:test         # Kotlin tests (jvm + macosArm64)
scripts/build-shared.sh [debug|release]         # → shared/build/XCFrameworks/<c>/ZenTaskShared.xcframework
scripts/build-mac.sh [Debug|Release]            # xcodegen + xcodebuild → build/ZenTask.app (ad-hoc signed)
(cd apps/macos && xcodegen generate && xcodebuild test -scheme ZenTask \
    -destination 'platform=macOS,arch=arm64' -derivedDataPath ../../build/DerivedData)
INSTALL=1 scripts/release.sh X.Y.Z [notes.md]   # bump, test, build, sign, verify, tag, push, GitHub release (+ Android/Wear APKs), install
DRY_RUN=1 scripts/release.sh X.Y.Z              # build + verify only
scripts/deploy.sh                               # server → vps1 (allowed without asking once tests pass)
swift scripts/make-icon.swift apps/macos/ZenTask/Assets.xcassets/AppIcon.appiconset docs/brand/zentask-logo.png apps/macos/ZenTask/ZenTask.icon
```

## Layout

- `VERSION` — single source of the version. Gradle generates `ZenTaskBuild.VERSION` from it;
  `release.sh` writes it into `apps/macos/project.yml` (`MARKETING_VERSION`).
- `shared/` — KMP (`jvm`, `macosArm64`, `android`), package `com.vuhpquang.zentask`, exported to Swift as
  `ZenTaskShared` via SKIE. Platform-free; `expect/actual` only where a platform really differs
  (SQLite driver, lock).
  - `model/` — one `@Serializable` data class per entity (spec §6) + `EntityTypes` registry. Property and
    entity names are a file/wire format: add, never rename. Enum-like values are Int/String constants.
  - `sync/` — `Hlc`, `SyncRecord` + per-field LWW `merge`, protocol DTOs, `SyncApi` (Ktor client),
    `SyncEngine` (push outbox / pull pages), `SyncScheduler` (debounce 2 s, every 5 min, on request).
  - `store/` — `ZenStore`: SQLDelight DB (`src/commonMain/sqldelight/`) with ONE generic `record` table
    (fields + clocks as JSON), the `pending_change` outbox and `sync_meta`. Every local write = record +
    outbox in one transaction. Typed models ↔ field maps via kotlinx.serialization (`toFields`/`fromFields`).
    Edits go through `update(type, id) { … }` (or `put(value, base = original)`) so a stale copy can't
    revert newer remote fields; plain `put` is for creating/importing. Changes the server refuses are
    *parked* in the outbox (kept, not resent until edited again); `clock_skew` ones are re-stamped.
  - `ticktick/` — one-way TickTick import (spec §10): `TickTickApi` (web API v2, cookie `t`, read-only,
    optional sources flagged) → `TickTickSnapshot` → `TickTickMapper` (deterministic ids `tt_<id>`, Inbox →
    `inbox`) → `TickTickSync` (per-record baseline in `ticktick_baseline`; gone → Trash/archive, never
    delete; mass-trash guard). `TickTickCsv` = one-off backup import, refused after any API sync.
  - `tasks/` — domain rules shared by the app and (P3b) MCP: `TaskIndex` (load once per data change,
    smart lists/sections/counts), `TaskActions` (create/complete incl. recurring copies/trash/edit/checklist —
    always via `store.update`), `Recurrence` (RRULE subset), `QuickAddParser` (EN/VI), `TagActions`,
    `ReminderPlanner`. Pure: clock and zone are parameters.
  - `lists/` — `ListActions` (lists/folders/sections, trash+restore by shared timestamp, Inbox protected,
    `SortOrder` gaps of 2^32 with renumber on exhaustion), `FilterRule` (custom smart lists, JSON in
    `Filter.rule`), `ViewOptions` (per-list sort/group, synced as Preference `view.<key>`).
  - `habits/` — `HabitSchedule` (TickTick rule formats: daily, BYDAY, `TT_TIMES=n` a week), `HabitStats`
    (day or week streaks), `HabitActions` (check-in id `chk_<habitId>_<yyyyMMdd>`, toggle or accumulate),
    `CountdownQueries`/`CountdownActions` (yearly repeats, 29 Feb → 28), `HabitBoard` (rows/cards for the UIs and MCP).
  - `calendar/` — `CalendarQueries` (Day/Week/Month content: visible events + dated tasks + countdowns + habits,
    all-day row, timed entries clipped per day with overlap columns; filtered by `CalendarViewOptions`, the
    synced Preference `calendar.view`: colours incl. one fixed habit/countdown colour, Classic/Modern item style,
    icons, year heatmap, checklist items, every repeat cycle (`kind = "repeat"`, read-only, `taskId` → the task),
    focus sessions, a second time zone), `Lunar` (Vietnamese lunar dates, UTC+7). Calendar records are server-owned; which
    calendars show is the Preference `calendar.hidden`.
  - `ZenTaskCore` — the Swift-facing entry point (typed helpers; Kotlin `Task` is `ZenTaskShared.Task` in Swift).
- `server/` — Ktor/JVM, depends on `shared` (same models, merge and protocol).
  `Application.module(config, now)`; `ServerConfig.fromEnv()` (`ZENTASK_PASSWORD` required, `HOST`
  defaults to 127.0.0.1). `ServerDb` = SQLite over JDBC (`records` with a global version, `devices`, `meta`
  with `serverId`, `mcp_audit`). Routes: `POST /v1/auth/login`, `GET/DELETE /v1/devices`, `POST /v1/sync`,
  `POST /mcp`; everything except login, health and the public pages needs a device bearer token.
  `calendar/`: Google OAuth (start → browser → public `/v1/calendar/google/callback`, one-time state),
  refresh tokens sealed with AES-GCM (`ZENTASK_ENCRYPTION_KEY`) in the server-only `calendar_credentials`
  table, `CalendarService.syncAll` every 10 min writing calendar records via `ServerDb.writeServer`
  (field diffs, server HLC node), `IcalParser` for secret links. `module(config, now, http)` takes the
  HttpClient so tests fake Google.
  `mcp/`: MCP (JSON-RPC over Streamable HTTP, written by hand) — `McpReplica` is a `ZenStore` at
  `<dataDir>/mcp.db` synced in-process with `ServerDb` (pull → shared TaskActions → push), so AI edits
  follow the app's rules; `McpTools` = the tool set; every write is audited.
- `apps/macos/` — xcodegen project (`project.yml`); never hand-edit or commit the `.xcodeproj`.
  Links the **release** XCFramework in both configs. `AppModel` (@Observable) owns `ZenTaskCore`; DB in
  `~/Library/Application Support/ZenTask[-dev]/zentask.db`; server token + TickTick cookie in `~/Library/Application Support/ZenTaskSecrets/<bundle id>.json` (0600; not the Keychain: a self-signed app gets a per-build keychain partition, so every update prompted twice; older Keychain items are moved on first read)
  (service = bundle id). Settings (⌘,) = Account + TickTick tabs. Tokens in `DesignSystem/Theme.swift`.
  Main window = `Tasks/TasksView` (sidebar | list | detail) driven by `TasksModel`, which reloads the
  `TaskIndex` off the main thread on `core.recordChanges` (80 ms debounce). `Services/Reminders` schedules
  local notifications from `core.plannedReminders`. The window is `MainWindow` = `AppRail` (always-visible
  icon rail, ⌘1–5) + the area. List management: `Tasks/ListSheets.swift` (sheets), sidebar context menus,
  drag and drop with task-id String payloads validated in `TasksModel.drop`. `Calendar/` (rail ⌘2):
  `CalendarModel` + `CalendarView` (Week/Day grid, Month, Unscheduled panel); Settings → Calendars (accounts and
  working hours: Day/Week open fitted to `calendar.workStart`–`workEnd`, 08–21 by default). Short blocks are one line
  (`TitleAndTime`: title truncates, time stays) with a gap between stacked blocks.
  `Habits/` (rail ⌘3): `HabitsModel` + `HabitsView` (list with a 7-day strip | detail with check-in, stats,
  month map) + `HabitEditor`. `Countdowns/` (rail ⌘4): `CountdownsModel` + `CountdownsView` (card grid, editor).
  Countdowns with "show in calendar" appear as all-day entries. Habits area = week progress rings + habits by
  `HabitSection` as cards (or rows) | detail. `Matrix/` (rail ⌘5): Eisenhower quadrants from `TaskIndex.matrix`,
  drag between quadrants sets priority. `Search/SearchOverlay` (rail search, ⌘K): tasks/notes, lists, smart lists,
  tags, accent-insensitive. `Calendar/CalendarChrome.swift`: left bar (mini month with lunar dates, show checkboxes),
  View options sheet, "+" new task. `Tasks/TaskComposer` = the new-task card (calendar "+", a range dragged on
  empty calendar space, global Quick Add): `~` list, `!` priority, `#` tag pickers, quick-add dates. Calendar day
  columns use a `DropDelegate` (`ColumnDrop`) that shows a 15-minute-snapped placeholder while dragging.
  `Focus/` (rail ⌘6): `FocusModel` (Pomodoro/stopwatch timer, state saved in UserDefaults `focus.state` so a
  relaunch resumes, finished sessions saved as synced `FocusRecord`s, end-of-phase notifications) + `FocusView`
  (dial, task picker, overview, record timeline); "Start focus" in the task "…" menu.
  List views (`Tasks/BoardViews.swift`): `KanbanBoard` (groups as columns; drops set section/priority/list via
  `TasksModel.drop(_:into:)`), `TimelineBoard` (dated tasks as bars, a week back to four weeks ahead),
  `SummaryView` (sidebar "Summary": done/open by list for a period, Copy as text). The view is picked in the list
  "…" menu (`TasksModel.viewMode`). The date popover has Date | Duration tabs (start–end, all day or timed).
  Appearance (`DesignSystem/Appearance.swift`, Settings → Appearance): System/Light/Dark plus themes (Default,
  Catppuccin, Dracula, Nord, Solarized, Rosé Pine) stored in UserDefaults `appearance.mode`/`appearance.theme`;
  `Themed()` on each window sets the scheme, accent (`.tint`), background and `themeColors` (rail/sidebar surface);
  lists use `themedListBackground`.
  `Hotkeys/HotKeys.swift`: global hotkeys via Carbon `RegisterEventHotKey` (Quick Add ⌃⌥N by default, show/hide
  window), stored in UserDefaults `hotkey.*`; Settings → Hotkeys records them and lists the app shortcuts.
  Task list chrome (`Tasks/ListChrome.swift`): `ListHeader` (☰ sidebar, list picker popover, `SortMenu` group/sort/
  order, "…" hide completed/details), `GroupHeaderRow` (collapsible groups, keyed by `TaskSection.key`, remembered in
  UserDefaults `tasks.collapsed`), `GroupBar` (list colour). `Calendar/CalendarPanels.swift`: `CalendarPanel` beside
  the task detail (own `PanelCalendar`/`CalendarModel`: Day, Agenda, Month), `AgendaTimeline` (also the main Agenda
  mode), `TaskPanel` beside the Calendar (own list/options in UserDefaults `calendar.panel.*`, drag onto the grid).
  In Swift, write `_Concurrency.Task { }` in files that import ZenTaskShared (Kotlin `Task` shadows it).
  UI snapshots: `ZENTASK_SNAPSHOT=/tmp/shots build/DerivedData/Build/Products/Debug/ZenTask.app/Contents/MacOS/ZenTask`.
- `apps/android/` — Jetpack Compose app (module `:android`, included only when `ANDROID_HOME` or `local.properties`
  exists). `applicationId com.vuhpquang.zentask` (`.dev` for debug), minSdk 34 (SQLite with `json_extract`),
  compileSdk 37, versionCode from `VERSION`. `ZenTaskApplication` sets `AndroidPlatform.context`; `ZenApp` owns
  `ZenTaskCore` (DB `zentask.db`), the server token (SharedPreferences), `index`/`revision` StateFlows and sync.
  UI: `MainActivity` (bottom bar Tasks/Calendar/Habits/Countdown/Focus), `ui/TasksScreen.kt` (drawer, list,
  quick add, detail), `ui/OtherScreens.kt`, `ui/Theme.kt`. Detail text fields keep drafts with the value they
  were loaded from and write only changed fields (debounced, on stop, on leave), so synced edits aren't reverted.
  `Reminders.kt`: exact alarms from `core.plannedReminders` (armed id=fireAt kept in prefs, only changes re-armed,
  a 12 h re-plan alarm, `BootReceiver` re-arms after reboot/update), notification with a Done action; also the
  pomo end alarm. The Focus timer lives in `ZenApp` (prefs `focus.*`), not in the screen. `TodayWidget.kt`: Glance
  home-screen widget (Today list, tick to complete), reloads on `ZenApp.revision`. Screens read data off the main
  thread with `loaded(keys) { … }`. Release APK is signed with the Mac's debug key (`~/.android/debug.keystore`:
  in-app updates only install over a copy signed with the same key, so keep that file).
  `AppUpdater.kt`: in-app update (GitHub latest release → `ZenTask-X.Y.Z-android.apk` → PackageInstaller,
  `UpdateInstallReceiver` shows the system confirm); checked on resume every 6 h; Settings → Updates.
  Build: `./gradlew :android:assembleDebug`; test on the emulator (`emulator -avd Medium_API37_1 -no-window`,
  drive with `adb shell input`, `adb exec-out screencap -p`). Install steps for the phone: setup-guide §7.
- `apps/wear/` — Wear OS app (module `:wear`, same inclusion rule as `:android`; same applicationId). Standalone:
  its own `ZenTaskCore` + DB, signs in once with the server password on the watch and syncs itself (no phone
  Data Layer). `WearZen` (state: today's open tasks + today's habits), `WearActivity` (Wear Material 3: Today list
  with tick to complete, habit check-in, sign in/sync/sign out). Emulator: `Wear_API36` (`-port 5556`).
- In-app updates (every platform must have one, owner's rule 2026-11-09; not the watch yet): shared
  `update/AppUpdates.kt` parses the latest GitHub release and names each platform's asset. Mac:
  `Services/Updater.swift` (from LaunchApp's updater; zip instead of dmg; checks the signer), Settings → General →
  Updates + app menu "Check for Updates…". Debug: `ZENTASK_UPDATE_DRYRUN=1 ZENTASK_PRETEND_VERSION=0.8.0` runs
  check → download → unpack → verify and prints the result. Windows (P8) must ship with one.
- `scripts/` — `lib/env.sh` (sourced by all), build, release, deploy, signing, icon.
- `docs/brand/zentask-logo.png` and `apps/macos/ZenTask/ZenTask.icon` (the app icon: Icon Composer bundle with a
  light and a dark gradient fill, so the Dock's dark/tinted icon styles keep ZenTask's colours) — generated by
  `make-icon.swift`; change the script, not the files. `AppIcon.appiconset` is the classic fallback.

## Notifications (P8)

- Planned in shared `notify/NotificationPlanner` (tasks, habits, countdowns, daily summary; spec §15), settings in
  the synced `NotificationPrefs` preference (`core.notificationPrefs()` / `setNotificationPrefs`).
  `core.plannedReminders(index, limit)` returns every kind; `PlannedReminder.kind` says which.
- Mac: `Services/Reminders.swift` (categories task/habit/plain, Snooze 5m/15m/1h/Tomorrow, Check in),
  Settings → Notifications (`Settings/NotificationSettings.swift`). Clicking a task notification opens
  `zentask://task/<id>`.
- Android: `Reminders.kt` (one channel "reminders"; Done / Check in + snooze 15 min / 1 hour; snoozes are
  alarms with `zs.` ids that `sync` leaves alone), Settings → Notifications (`ui/NotificationSettings.kt`).
- New dated tasks get the default reminder via `TaskActions.defaultReminders` (set by the core from the prefs).
- All-day triggers at 09:00 (every preset, TickTick's default) and "on time" fire at the prefs' all-day time;
  other explicit times stay. Habit reminder times: `HabitActions.setReminders` (Mac habit editor → Reminders).

## Android parity (P9)

- Tasks (P9a): swipe right = complete / reopen, left = Trash with an Undo snackbar (`ui/TaskExtras.kt`
  `SwipeTaskRow`; the snackbar runs in the list's scope because the row leaves the list); tag chips + tag dialog
  and the "…" menu (pin, duplicate, Won't do / Reopen, Trash; Restore / Delete forever in Trash) on task detail.
- Habits & countdowns (P9b, `ui/HabitEditors.kt`): one editor for new/edit habit (colour, yes-no/amount, weekdays,
  reminder times; rules it can't show are kept unless days are picked), a detail sheet (streaks, month grid to
  check in past days, Edit / Archive / Delete), countdown editor + Edit / Archive / Delete, "Archived" toggles.
- Calendar (P9c, `ui/CalendarScreens.kt`): Day / 3 days / Week time grid (all-day row, now line, opens at the hour in
  Settings → Calendar, tap an empty slot = new 30-min task), Month grid (tap a day = Day view), List (2 weeks),
  swipe left/right to page, View options sheet writing the synced `CalendarViewOptions`. Tapping a task opens it via
  `ZenApp.openTask`.
- Boards (P9d, `ui/Boards.kt`): "Eisenhower Matrix" in the drawer (`ListPick.Matrix`; long-press a task to move it to
  another quadrant = set priority); a list's "…" → "View as kanban" (`listActions.setViewMode`, synced with the Mac):
  columns per section, add a task at the bottom of a column, long-press to move it to another section, add /
  rename / delete sections. An empty list still shows one column.
- Compose gotcha: `Modifier.border(0.dp, …)` still draws a hairline; add the border conditionally.

## Gotchas

- JDK: the default Oracle JDK 18 on this Mac is x86_64 ("Bad CPU type"). `scripts/lib/env.sh`
  forces Corretto 17 arm64 via `/usr/libexec/java_home -v 17`. Run Gradle through it.
- Versions: Kotlin 2.4.20 is pinned to what SKIE 0.10.15 supports; bump both together.
  Xcode 27 is newer than K/N tests against (`kotlin.apple.xcodeCompatibility.nowarn=true`).
- Kotlin/Native targets only configure on macOS hosts (`HostManager.hostIsMac`).
- vps1 has ~2 GB RAM: never build with Gradle there. `deploy.sh` builds `:server:installDist` on the
  Mac and ships it into a runtime-only `eclipse-temurin:17-jre` image.
- Signing: self-signed "ZenTask Self-Signed" (SHA-1 `8ad13567…523c`), backup + p12 password in
  `~/.config/zentask-signing/` (never commit, never print the password). `release.sh` refuses any
  other signer. Recreate only with `scripts/make-signing-identity.sh` on a new Mac (import the p12 instead).
- The ECC Bash hook blocks commands that contain both `git commit` and some `-n`-style flags
  (false "--no-verify" match): write files first, commit in a separate command.
- Don't run `npx gitnexus analyze` inside this repo.
- Dock / ⌘-Tab showing an old icon after an icon change: the Dock has its own icon cache
  (`$(getconf DARWIN_USER_CACHE_DIR)com.apple.dock.iconcache`). Run `scripts/refresh-icon-cache.sh` (moves the cache
  aside, restarts the Dock). `release.sh` also touches the installed bundle, since `ditto` keeps its old date.
- macOS `List` keeps the estimated row height (`defaultMinListRowHeight`, 40 pt) for rows filled while it
  isn't on screen, so a row's date line is drawn under the next row until the list is rebuilt.
  `.remeasuresRows(data:hasRows:)` (`Tasks/RowRemeasure.swift`) rebuilds the rows once after the first data and
  when the window comes back visible after the data changed behind it. Use it on any new task `List`.
- The main window has no title bar (`.windowStyle(.hiddenTitleBar)`): the traffic lights sit over the rail in every
  area. Don't add window toolbar items (they float over the content); put actions in the area's own header.
  Debug: `open -n build/ZenTask.app --env ZENTASK_OPEN_AREA=calendar` opens straight on Calendar.
- The macOS app links `-lsqlite3` (SQLDelight's native driver); keep it in `project.yml` `OTHER_LDFLAGS`.
- This office network inspects TLS (Fortinet): the Android emulator doesn't trust it, so anything hitting
  GitHub/the server from the emulator fails with "Trust anchor not found". To test, build a throwaway APK with a
  network security config that trusts the proxy CA (never commit it).
- Sync safety tests live in `shared/src/commonTest/.../sync` and `server/src/test/.../SyncEndToEndTest.kt`
  (two client stores through an in-process server). Any change to merge/outbox/sync must keep them green
  and add a case for the new path.

## Releasing

`INSTALL=1 scripts/release.sh X.Y.Z notes.md` from a clean `main`. Then bump the hub pointer:
`cd ../.. && git add -f projects/zentask && git commit -m "chore: bump zentask submodule (ZenTask X.Y.Z)"`.

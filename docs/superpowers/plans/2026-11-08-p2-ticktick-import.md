# P2 — Import & Sync TickTick Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pull everything from TickTick into ZenTask, as often as the owner likes, without duplicates
or lost edits. The pull uses the web API v2 with the owner's session cookie, with a one-off CSV import
as fallback. The macOS app gets a minimal screen to log in to the ZenTask server, paste the cookie,
preview a TickTick sync and run it.

**Architecture:** Everything lives in `shared/.../ticktick/`:
- `TickTickApi` (Ktor) fetches a `TickTickSnapshot`.
- `TickTickMapper` turns the snapshot into ZenTask entities with **deterministic ids**
  (`tt_<ticktickId>`), so two devices importing the same data produce the same records.
- `TickTickSync` merges against a per-record **baseline**, the TickTick values as of the last pull,
  stored in a new local table `ticktick_baseline` (spec §10.3).
- `CsvImporter` parses the backup into the same snapshot shape, so it reuses the mapper and the merge.

Writes go through `ZenStore`, so they land in the outbox and sync to the server like any local edit.

**Tech Stack:** as P1. Ktor client (cookie auth), kotlinx.serialization, SQLDelight. SwiftUI for the
screen, with Keychain via `Security`.

**Spec:** `docs/superpowers/specs/2026-11-08-zentask-design.md` §10 (also §6, §7.1 recurring copies, §8 Keychain).

## Global Constraints

- One-way only. Never send anything but GETs, plus the read-only `habitCheckins/query` POST, to TickTick.
- Merge rule (spec §10.3, verbatim intent):
  - A field TickTick changed since the baseline overwrites ZenTask.
  - A field TickTick did not change keeps the ZenTask value.
  - A new record is created.
  - A record that disappeared from TickTick goes to ZenTask's Trash (`trashedAt`), never a permanent delete.
  - A record without `ticktickId` is never touched.
- Pressing Sync twice must not duplicate anything: ids are deterministic and the merge is idempotent.
- Never use TickTick's name or branding in the UI beyond the plain words "TickTick" in labels that name
  the source ("Sync from TickTick").
- The cookie lives in the Keychain only. It is never logged, never sent to the ZenTask server, and never
  committed.
- macOS UI: design rules (`.claude/rules/design.md`); Settings is a stock `Form`, `.formStyle(.grouped)`.

## Rulings made while planning

- **Deterministic ids:**
  - Most records: `tt_<ticktickId>`.
  - Tags: `tt_tag_<name>`.
  - The TickTick Inbox maps to ZenTask's fixed `inbox`.
  - CSV rows have no stable id (spec §10), so the CSV import uses the temporary key
    `csv_<sha1(list|title|createdTime)>` and sets no `ticktickId`. A later API sync therefore does not
    match CSV rows.
  - The CSV is offered only when ZenTask has no TickTick data yet. Using it and then the cookie would
    duplicate tasks. The UI says so.
- **"Disappeared"** means a task is absent from open tasks, from completed/won't-do tasks, and from
  TickTick's trash. Tasks in TickTick's trash are moved to ZenTask's Trash too.
- **Mass-trash guard:** if a sync would trash more than 10 records **and** more than 20% of what was
  imported before, `apply` refuses unless the call is explicitly confirmed. The preview shows the
  number. This guards against an API change or a partial response emptying the lists.
- **Records without Trash** (tag, folder, column, habit, countdown, checklist item) that disappear:
  - Habit and countdown get `archivedAt`.
  - Checklist items are tombstoned with their task. They are part of the task in TickTick.
  - Tag, folder and column are left alone, and the count is reported.
- **TickTick status `1`** (67 rows in the backup, all with a completed time) is stored as-is (spec §6).
  The app treats it as completed from P3.
- **Partial availability:**
  - Only `batch/check/0` and `project/all/completed` are required; if either fails, the sync aborts.
  - Every other source is optional: won't-do (`project/all/closed`), trash, columns, habits, checkins
    and countdowns. Each optional source sets an availability flag on the snapshot.
  - A record kind whose source was unavailable is never trashed or archived.
  - Baseline won't-do tasks are not trashed when won't-do is unavailable.
  - Endpoints were checked against the open-source ticktick-py client (`batch/check/0`,
    `project/all/completed?from=…&to=…&limit=100` in UTC `yyyy-MM-dd HH:mm:ss`, `x-device` header).
- **Countdowns:** the endpoint is not confirmed (no HAR yet). The client calls it, and if it fails the
  sync still succeeds and reports "countdowns: not available".

## Review Focus

1. Sync twice with an unchanged snapshot creates nothing, updates nothing, trashes nothing, and leaves
   the outbox empty after the first sync.
2. A ZenTask edit to a field TickTick didn't touch survives the next sync. A field TickTick did change
   wins even if ZenTask also changed it.
3. An empty or partial TickTick response (expired cookie returning `[]`, API change) must not trash
   everything. The mass-trash guard applies, and a 401/HTML login page is reported as "cookie expired".
4. Records created in ZenTask, i.e. with no `ticktickId`, are never modified or trashed by a sync.
5. A task that reappears in TickTick after being trashed by a sync is un-trashed.

---

### Task 1: Snapshot model + mapper

**Files:**
- Create: `shared/src/commonMain/kotlin/com/vuhpquang/zentask/ticktick/TickTickModels.kt`
  (`@Serializable` DTOs, tolerant: `ignoreUnknownKeys`, all fields optional)
- Create: `shared/src/commonMain/kotlin/com/vuhpquang/zentask/ticktick/TickTickMapper.kt`
- Test: `shared/src/commonTest/kotlin/com/vuhpquang/zentask/ticktick/TickTickMapperTest.kt`

**Interfaces:**
- Produces:
  - `data class TickTickSnapshot`, with the fields:
    - `inboxId: String?`
    - `groups: List<TtGroup>`, `projects: List<TtProject>`, `columns: List<TtColumn>`
    - `tasks: List<TtTask>`, which holds open, completed and won't-do tasks
    - `trashedTaskIds: Set<String>`
    - `tags: List<TtTag>`, `habits: List<TtHabit>`, `checkins: List<TtCheckin>`, `countdowns: List<TtCountdown>`
    - `countdownsAvailable: Boolean`
  - DTO field names follow TickTick's JSON (`projectId`, `startDate`, `dueDate`, `isAllDay`, `timeZone`,
    `repeatFlag`, `repeatFrom`, `reminders[{trigger}]`, `items[]`, `completedTime`, `createdTime`,
    `modifiedTime`, `sortOrder`, `kind`, `parentId`, `columnId`, `pinnedTime`, `tags[]`, …).
  - `object TickTickMapper`:
    - `fun map(s: TickTickSnapshot): List<Mapped>`
    - `data class Mapped(val type: EntityType<*>, val entity: Entity)`
    - `fun id(ticktickId: String): String = "tt_$ticktickId"`
    - `fun parseTime(s: String?): Long?` accepts `2026-10-30T04:00:00+0000`, `…000+0000` and `Z` forms.
  - Reminders: `trigger` values are stored as `TRIGGER:<value>`, with the `TRIGGER:` prefix added if missing.
  - Checklist items become `ChecklistItem` with `taskId = tt_<task>`.
  - The inbox project id maps to `"inbox"`.
  - `ticktickId` is set on every mapped entity.

- [ ] **Step 1: Write the failing tests:**
  - `mapsTaskFields`: priority, status, dates, all-day, time zone, tags, reminders, sort order,
    `projectId → tt_…`, parent, column.
  - `inboxTasksGoToInbox`.
  - `checklistItemsBecomeEntities`.
  - `parsesAllTickTickTimeForms`.
  - `idsAreDeterministic`: mapping twice gives equal lists.
  - `tagsAreKeyedByLowercaseName`.
  - `habitAndCheckinMapping`.
  - `unknownJsonFieldsAreIgnored`: decode a fixture with extra keys.
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*TickTickMapper*'`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :shared:allTests`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ticktick): snapshot model and mapper to ZenTask entities`, then push.

### Task 2: Baseline merge (`TickTickSync`)

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/vuhpquang/zentask/db/TickTick.sq`, with the table
  `ticktick_baseline(entity TEXT, id TEXT, fields TEXT, PRIMARY KEY(entity,id))` plus `ticktick_meta`
  (key/value: `lastSyncAt`, `source`).
- Create: `shared/src/commonMain/kotlin/com/vuhpquang/zentask/ticktick/TickTickSync.kt`
- Modify: `ZenStore`. Expose `database` internally (or add baseline accessors) so `TickTickSync` shares
  its lock and transaction.
- Test: `shared/src/commonTest/kotlin/com/vuhpquang/zentask/ticktick/TickTickSyncTest.kt`

**Interfaces:**
- Consumes: Task 1 `TickTickMapper`; P1 `ZenStore.put(type, value, base)`.
- Produces:
  - `class TickTickSync(store: ZenStore)`:
    - `fun preview(snapshot): SyncPlan`
    - `fun apply(snapshot, confirmMassTrash: Boolean = false): SyncPlan`. It throws `MassTrashException`
      when the guard trips without confirmation.
  - `data class SyncPlan(val created: Int, val updated: Int, val trashed: Int, val restored: Int, val untouchedRemoved: Int, val needsConfirmation: Boolean)`
  - Algorithm per mapped record:
    - No local record: create it. The baseline becomes the mapped fields.
    - A local record exists:
      - fields where mapped ≠ baseline are written to ZenTask;
      - a record that was trashed by sync and reappears is un-trashed: `trashedAt = null`;
      - the baseline becomes the mapped fields.
    - Every baselined record absent from the snapshot is trashed or archived according to the rulings.
  - All of it runs in one transaction. `lastSyncAt` is set.

- [ ] **Step 1: Write the failing tests:**
  - `firstSyncCreatesEverything`.
  - `secondIdenticalSyncChangesNothing`: counts are 0 and the outbox is unchanged.
  - `zentaskEditOfUntouchedFieldSurvives`.
  - `tickTickChangeWinsOverZenTaskEdit`.
  - `disappearedTaskGoesToTrashNotDeleted`.
  - `reappearedTaskIsRestored`.
  - `zentaskOnlyRecordsAreNeverTouched`.
  - `massTrashNeedsConfirmation`: 100 imported, a snapshot with 0 tasks throws; with confirm it trashes 100.
  - `habitDisappearanceArchives`.
  - `previewDoesNotWrite`.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :shared:allTests`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ticktick): baseline merge for repeatable one-way sync`, then push.

### Task 3: TickTick web API client

**Files:**
- Create: `shared/src/commonMain/kotlin/com/vuhpquang/zentask/ticktick/TickTickApi.kt`
- Test: `shared/src/commonTest/kotlin/com/vuhpquang/zentask/ticktick/TickTickApiTest.kt` (MockEngine fixtures)

**Interfaces:**
- Produces `class TickTickApi(http: HttpClient, cookie: () -> String, base: String = "https://api.ticktick.com")`
  with `suspend fun snapshot(): TickTickSnapshot`. It throws `TickTickException(kind)` with
  `kind ∈ COOKIE_EXPIRED | NETWORK | UNEXPECTED`.
  - Endpoints (GET unless noted). Every request sends `Cookie: t=<value>` and an `X-Device` JSON header
    as the web app does.
    - `/api/v2/batch/check/0`, which returns `inboxId`, `projectProfiles`, `projectGroups`,
      `syncTaskBean.update` and `tags`.
    - `/api/v2/column/project/{id}` for each project.
    - `/api/v2/project/all/completedInAll/?from=&to=&limit=100`, paged backwards by `completedTime`
      until a page comes back empty. Also `status=-1` for won't-do.
    - `/api/v2/project/all/trash/pagination?start=0&limit=100`, paged.
    - `/api/v2/habits`.
    - POST `/api/v2/habitCheckins/query` with `{habitIds, afterStamp: 20000101}`.
    - `/api/v2/countdown/list`, tolerant (see the rulings).
  - These map to `COOKIE_EXPIRED`: a 401/403, an HTML body, or a `batch/check` without `inboxId`.

- [ ] **Step 1: Write the failing tests:**
  - `buildsSnapshotFromAllEndpoints` (fixtures).
  - `sendsCookieAndDeviceHeaderAndOnlyReads`: every request is a GET, except the checkin query.
  - `completedTasksArePagedUntilEmpty`.
  - `htmlLoginPageMeansCookieExpired`.
  - `missingCountdownEndpointIsTolerated`.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :shared:allTests`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ticktick): web API v2 client (cookie, read-only)`, then push.

### Task 4: CSV import (fallback)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/vuhpquang/zentask/ticktick/TickTickCsv.kt` (RFC 4180
  parser; the header row starts at `"Folder Name"`)
- Test: `shared/src/commonTest/kotlin/com/vuhpquang/zentask/ticktick/TickTickCsvTest.kt` (inline sample),
  plus `shared/src/jvmTest/.../TickTickCsvRealFileTest.kt`. That test runs only if
  `~/Downloads/TickTick-backup-2026-11-08.csv` exists, and otherwise is skipped with a message.

**Interfaces:**
- Produces `object TickTickCsv { fun parse(text: String): TickTickSnapshot }`.
  - Lists and folders are derived from the `List Name`/`Folder Name` columns. Ids:
    `csv_<sha1(...)>`. `List Name == "Inbox"` maps to `inbox`.
  - Checklist content: lines starting with `▫` (open) or `▪` (done) become items. Empty items are
    skipped and the rest of the text stays in `desc`.
  - Reminders: split concatenated durations (`-P0DT15H0M0SP0DT9H0M0S` gives 2 triggers).
  - `parentId`: CSV sequence numbers are resolved within the file.
  - Columns come from `Column Name` per list.
- `TickTickSync.importCsv(snapshot)` refuses (`IllegalStateException`) if any TickTick baseline exists.

- [ ] **Step 1: Write the failing tests:**
  - `parsesQuotedMultilineFields`.
  - `checklistLinesBecomeItems`.
  - `splitsConcatenatedReminders`.
  - `inboxListMapsToInbox`.
  - `realBackupCounts`: 824 tasks, 8 lists, 1 folder, 16 checklists, status counts {2:469, 0:286, 1:67, -1:2}.
  - `csvImportRefusedAfterApiSync`.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :shared:allTests`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ticktick): one-off CSV backup import`, then push.

### Task 5: macOS — account, TickTick screen, background sync

**Files:**
- Create: `apps/macos/ZenTask/Services/Keychain.swift` (generic password items, service
  `com.vuhpquang.zentask`, accounts `serverToken` and `ticktickCookie`).
- Create: `apps/macos/ZenTask/Services/AppModel.swift`. `@MainActor @Observable` owns:
  - `ZenTaskCore` (DB at `~/Library/Application Support/ZenTask/zentask.db`, or the `.dev` folder for Debug)
  - the sync engine and scheduler
  - `syncStatus`
  - the TickTick state
- Create: `apps/macos/ZenTask/Settings/SettingsView.swift`, a stock Settings window with two tabs:
  - **Account**: server URL (read-only), password field, "Sign in" / "Sign out", sync status, "Sync now".
  - **TickTick**:
    - a cookie field, "Save cookie", and a link to the setup guide;
    - "Preview", which shows the create/update/trash counts, then "Sync from TickTick";
    - the last sync time and the result;
    - "Import CSV backup…" (NSOpenPanel), shown only before any TickTick sync.
- Modify: `RootView.swift`. Show the counts of lists, tasks and completed tasks, and the sync status,
  until P3 builds the real UI.
- Modify: `ZenTaskCore.kt`. Add Swift-friendly suspend wrappers: `login`, `syncNow`,
  `ticktickPreview(cookie)`, `ticktickApply(cookie, confirm)`, `importCsv(text)`, `counts()`, and the
  status as a `Flow` (SKIE).
- Test: `apps/macos/ZenTaskTests/KeychainTests.swift` (uses a test service name). Also run the
  `design-check` skill on the Settings tabs and RootView, light and dark.

- [ ] **Step 1: Write the failing tests:** `KeychainTests.testRoundTripAndDelete`, and
  `ZenTaskCoreTest.countsReflectImportedData` (shared jvmTest).
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the Xcode tests and `./gradlew :shared:allTests :server:test`. Expected: PASS.
  Then do the design-check snapshots.
- [ ] **Step 5: Commit** `feat(macos): sign in, TickTick sync screen, background sync`, then push.

### Task 6: Docs + release

- `docs/setup-guide.md` §5: step by step for getting the `t` cookie from Chrome/Safari DevTools, pasting
  it in Settings → TickTick, and what "cookie expired" means.
- `CLAUDE.md`: the ticktick package. Spec status: P2.
- Release `0.2.0` with `INSTALL=1 scripts/release.sh 0.2.0 notes.md`, then bump the hub pointer.

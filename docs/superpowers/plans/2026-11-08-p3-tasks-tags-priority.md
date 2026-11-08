# P3 — Tasks, Tags, Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ZenTask can be used every day for tasks on the Mac. That means:
- smart lists and the imported lists;
- create, edit, complete and trash tasks;
- subtasks and checklists;
- tags (nested, coloured);
- priority;
- quick add with `#tag !priority ^list` and dates in English or Vietnamese;
- local reminders.

**Architecture:**
- Domain rules live in `shared/.../tasks/`, so the app and the P3b MCP server share them:
  - `TaskQueries`: smart lists, grouping, sorting.
  - `TaskActions`: complete/uncomplete, including recurring tasks per spec §7.1; trash/restore; move.
  - `Recurrence`: the RRULE subset.
  - `QuickAddParser`.
  - `TagActions`.
  These are pure functions over `ZenStore` with an injected clock and time zone.
- The macOS app gets a three-column `NavigationSplitView`: sidebar | task list | detail. It is driven by
  an `@Observable` `TasksModel`, which observes `ZenStore` flows via SKIE.

**Tech Stack:**
- As P2.
- `kotlinx-datetime` 0.7.x for local dates, time zones and recurrence arithmetic in common code.
- `UserNotifications` for reminders.

**Spec:** `docs/superpowers/specs/2026-11-08-zentask-design.md` §3 (priority 1), §6 (Task, ChecklistItem, Tag), §7.1 (recurring copies, Trash), §11 (UI, quick add, colours, shortcuts, reminders).

## Global Constraints

- Every edit goes through `ZenStore.update` (or `put(value, base)`). Never a plain `put` of an edited copy (P1 review).
- Priority values 0/1/3/5. Colours:
  - high: red
  - medium: yellow/orange
  - low: blue
  - none: grey
  
  Each colour is a token in `Theme`, resolved per appearance. The colours are ZenTask's own; no TickTick assets.
- Status: 0 open, 2 completed, -1 won't do. TickTick's legacy 1 counts as completed everywhere.
- Smart lists are computed, never stored: All, Today, Tomorrow, Next 7 Days, Inbox, Completed, Won't Do, Trash.
- Keyboard:
  - ⌘N: new task (focus quick add)
  - Space: complete the selected task
  - ⌫: move to Trash
  - ↵: edit the title
  - Esc: leave the field
  - ⌘1…⌘5: rail tabs. Only Tasks exists in P3; the others are placeholders, hidden.
- Native look: SF Symbols, system fonts, sidebar material, Liquid Glass only on floating surfaces.
  Light and dark both checked with `design-check`.
- Typing and scrolling must not stutter with 1000+ tasks. Lists are computed off the main thread when
  they are large; `List` row identity is stable.

## Rulings made while planning

- **Recurring completion (spec §7.1):** completing a task with `repeatRule` does three things:
  1. writes a completed copy, with a new id, `status = 2`, `completedAt = now`, `repeatTaskId = original.id`
     and the same title, tags and list;
  2. moves the original to the next occurrence, computed from `dueDate` (`repeatFrom = "due"`, the
     default) or from the completion time (`repeatFrom = "completion"`);
  3. leaves the original open. If the rule has ended (COUNT/UNTIL), the original is completed instead
     and no copy is made.
  
  Supported RRULE subset:
  - `FREQ` = DAILY, WEEKLY, MONTHLY, YEARLY
  - `INTERVAL`, `BYDAY` (weekly), `BYMONTHDAY`, `COUNT`, `UNTIL`
  
  Anything else is "unsupported": completing completes once, and the detail pane says it repeats in a
  way ZenTask can't edit yet.
- **"Today"** includes overdue open tasks (as TickTick does), grouped as "Overdue" first. Dates are
  evaluated in the Mac's current time zone. All-day tasks use their stored local midnight.
- **Subtasks** are tasks with `parentId`. They are shown indented under the parent in list views, one
  level deep in P3.
- **Checklist items** belong to `CHECKLIST`-kind tasks. Converting between kinds is not in P3.
- **Quick add** syntax:
  - `#tag` (creates the tag if new)
  - `!high/!medium/!low/!none` or `!1..!3` (1 = high)
  - `^list` (prefix match on list name, case- and diacritic-insensitive)
  - dates: "today/tomorrow/mon…sun/next week/in 3 days/9am/21:30" and Vietnamese
    "hôm nay/mai/ngày mai/thứ 2…chủ nhật/tuần sau/9h/21h30/sáng/chiều/tối"
  
  Matched tokens are removed from the title. The quick-add field shows what was recognised as chips
  before ↵.
- **Reminders:** local notifications are scheduled for the next 14 days of open tasks with reminders,
  rescheduled on every change (debounced) and at launch. Triggers relative to the due date or start
  date (`TRIGGER:-PT15M`, `TRIGGER:PT0S`, `-P1D`) are supported. All-day tasks use a 09:00 default when
  the trigger is `PT0S`.
- **The list column is ordered** by the list's TickTick `sortOrder` within groups, matching the
  imported order. Drag to reorder comes in P4.

## Review Focus

1. A recurring task completed twice quickly, or completed on two devices offline, must not lose the
   original. The worst case is two completed copies, never zero open originals.
2. Quick add on Vietnamese input with diacritics, and titles that merely contain `#`, `!` or `^` in the
   middle of a word ("C# notes", "wow!"), must not be mangled.
3. Time zones and DST: a due date at 23:30 local in Today/Tomorrow; all-day tasks imported with
   `Asia/Saigon` vs `Asia/Ho_Chi_Minh`.
4. 1000+ tasks: switching lists and typing in quick add stay responsive (no main-thread decoding of
   every record per keystroke).
5. Undo-ability: trash is reversible from the Trash smart list. Won't Do and complete can be toggled
   back.

---

### Task 1: Dates, recurrence and smart lists (shared)

**Files:**
- `gradle/libs.versions.toml` + `shared/build.gradle.kts`: `kotlinx-datetime`.
- Create `shared/src/commonMain/kotlin/com/vuhpquang/zentask/tasks/Recurrence.kt`. Its API is
  `object Recurrence { fun next(rule: String, from: Instant, zone: TimeZone, isAllDay: Boolean, occurrence: Int): Instant?; fun isSupported(rule: String): Boolean }`.
  Here `occurrence` is the count completed so far (for COUNT), and the result is null when the rule has ended.
- Create `.../tasks/SmartList.kt`. `enum class SmartList { ALL, TODAY, TOMORROW, NEXT_7_DAYS, INBOX, COMPLETED, WONT_DO, TRASH }`
  and `sealed interface TaskFilter { Smart(list); Project(id); Tag(name) }`.
- Create `.../tasks/TaskQueries.kt`:
  - `fun tasks(store, filter, now, zone): List<TaskRow>` (top-level tasks with their subtasks and
    checklist progress), plus `fun sections(rows, filter): List<TaskSection>`. Sections:
    Overdue/Today/Tomorrow/… for date lists, columns for lists with columns, and "Completed" last.
  - `fun counts(store, now, zone): Map<TaskFilter, Int>` for sidebar badges.
  - `data class TaskRow(val task: Task, val subtasks: List<Task>, val checklistDone: Int, val checklistTotal: Int, val projectName: String, val overdue: Boolean)`.
- Tests: `RecurrenceTest` and `TaskQueriesTest`:
  - daily/weekly BYDAY/monthly day 31 → the last day of shorter months, as TickTick does
  - COUNT end, UNTIL end
  - unsupported rule
  - DST-free zone and a DST zone (`America/Los_Angeles`)
  - Today includes overdue
  - Tomorrow boundaries at 23:30
  - Trash only `trashedAt`
  - status 1 counted as completed
  - subtasks nested under their parent and excluded from the top level
  - tag filter is case-insensitive
- Commit `feat(tasks): recurrence, smart lists and task queries`.

### Task 2: Task actions (shared)

**Files:** `.../tasks/TaskActions.kt` and `TaskActionsTest`.
- API:
  - `create(store, title, filter, parsed: QuickAdd?)` gives a new task in the right list/date for the
    current filter. Today → due today all-day; a tag filter → that tag.
  - `complete(id)`, `uncomplete(id)`, `wontDo(id)`.
  - `trash(id)` cascades to subtasks; `restore(id)`; `deleteForever(id)` only from Trash.
  - `setPriority`, `setDue(id, date?, allDay)`, `setTags`, `move(id, projectId)`, `rename`, `setContent`.
  - Checklist: `addItem`, `toggleItem`, `renameItem`, `deleteItem`.
  - Subtasks: `addSubtask(parentId, title)`.
- Every action uses `store.update`. Recurring completion is as ruled above.
- Tests:
  - `completingRecurringTaskMakesCopyAndAdvances`
  - `completingTwiceMakesTwoCopiesAndKeepsOriginalOpen`
  - `endedRuleCompletesOriginal`
  - `uncompleteRestoresStatus`
  - `trashCascadesToSubtasksAndRestoreBringsThemBack`
  - `deleteForeverOnlyFromTrash`
  - `createInTodayGetsTodayDue`
  - `editsUseUpdateNotStaleCopies`: a remote title change between read and action survives `setPriority`.
- Commit `feat(tasks): task actions incl. recurring completion and trash`.

### Task 3: Quick add parser + tags (shared)

**Files:** `.../tasks/QuickAddParser.kt`, `.../tasks/TagActions.kt`, plus their tests.
- `QuickAddParser.parse(text, now, zone, projects: List<Project>, tags: List<Tag>): QuickAdd`, with
  `data class QuickAdd(val title: String, val tags: List<String>, val priority: Int?, val projectId: String?, val due: Instant?, val allDay: Boolean, val tokens: List<Token>)`.
  `Token` carries a range plus a kind, so the UI can show chips.
- Token rules:
  - Tokens must start at a word boundary (start of text or after whitespace).
  - `#` followed by letters, numbers, `_`, `-` or `/` (nested tags `parent/child`).
  - Diacritics are folded only for `^list` matching.
- `TagActions`: `rename(old, new)` rewrites the tag on every task; `setColor`; `setParent`; `delete`
  removes the tag from every task and trashes nothing. All of it goes through `update`.
- Tests:
  - English and Vietnamese dates
  - `C# notes` and `wow!` untouched
  - `^works` matches "🏢Works"
  - `!1` is high
  - `#new/tag`
  - a time without a date means today, or tomorrow if already past
  - multiple tokens
  - an empty title after removing tokens keeps the raw text
  - rename and merge of tags
- Commit `feat(tasks): quick add parser (EN/VI) and tag actions`.

### Task 4: macOS task UI

**Files** (all under `apps/macos/ZenTask/Tasks/`):
- `TasksModel.swift`: `@MainActor @Observable`. It holds the selection, current filter, rows and
  sections, observes the store and recomputes off the main thread, and calls the shared actions.
- `TasksView.swift` (the split view)
- `SidebarView.swift`: smart lists with counts; lists grouped by folder (collapsible); tags with colour dots.
- `TaskListView.swift`:
  - quick-add field on top with chips;
  - sections, with collapsible "Completed";
  - rows with a priority-coloured checkbox, title, due date (red if overdue), list name in smart
    lists, tag pills, checklist progress, and subtasks indented.
- `TaskDetailView.swift`:
  - title and notes (multi-line);
  - due date/time popover (`DatePicker` plus all-day toggle and "Clear");
  - priority menu, tags token field, list picker;
  - checklist editor or subtasks;
  - repeat (read-only description) and reminders (read-only list in P3);
  - created/completed timestamps.
- `Theme.swift` additions: priority colours and the row metrics.
- `RootView.swift` is replaced by `TasksView`. The counts screen goes away, and the sync status moves
  to the sidebar footer with a sync button.
- Keyboard shortcuts as in Global Constraints. Menu commands go in `ZenTaskApp` (`CommandMenu("Task")`).

**Tests:**
- `TasksModelTests` (Swift, in-memory core): switching filter updates rows; complete via the model
  removes the row from Today and adds it to Completed; quick add creates a task with the parsed fields.
- Snapshots (`ZENTASK_SNAPSHOT`), light and dark: Today with imported data, Inbox, a list with
  columns, Trash, a detail pane with checklist, quick add with chips.
- `design-check`.

Commit `feat(macos): task lists, detail pane, quick add`.

### Task 5: Reminders

**Files:**
- `apps/macos/ZenTask/Services/Reminders.swift`, using `UNUserNotificationCenter`.
- Permission is requested the first time a task with a reminder exists, or from a button in
  Settings → General.
- Shared: `ReminderPlanner.upcoming(store, now, zone, days = 14): List<PlannedReminder(id, taskId, title, fireAt)>`,
  pure and tested.
- Notification actions: "Complete" and "Snooze 15 min". "Complete" runs `TaskActions.complete`.

**Tests:**
- `ReminderPlannerTest`:
  - relative triggers
  - all-day default 09:00
  - past triggers skipped
  - completed and trashed tasks skipped
  - 14-day window
  - recurring task: only the next occurrence

Commit `feat: local reminders for tasks`.

### Task 6: Docs, review, release

- Update `CLAUDE.md` (the tasks package, the Tasks UI files) and the spec status.
- Run a whole-branch review and fix what it finds.
- Run `INSTALL=1 scripts/release.sh 0.3.0 notes.md` and bump the hub pointer.
- Next comes P3b (MCP), spec §10a.

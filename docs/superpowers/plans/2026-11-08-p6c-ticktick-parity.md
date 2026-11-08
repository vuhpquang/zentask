# P6c — TickTick parity from a hands-on survey

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** close the gaps found by opening the owner's TickTick Mac app (2026-11-08, with the owner's go-ahead,
view-only: menus and dialogs opened, no data changed) and comparing it screen by screen with ZenTask, plus the
review minors deferred in P6. ZenTask keeps its own look (blue→violet, no TickTick images or copy).

**Survey notes (what TickTick does):**
- Calendar: header `◧ November 2026 … [+] [Week ˅] [‹ Today ›] […]`; "…" = View Options, Arrange tasks, Calendar
  Subscription. ◧ opens a left bar: mini month (lunar dates, dots on busy days, current week highlighted) and
  "show" checkboxes for All / Lists / Tags / Filters / Calendar subscriptions. View Options: Color (tasks by
  priority…), Show Completed, Show Checklist Item, Show All Repeat Cycles, Show Habit, Show Countdown, extra time
  zone. Day headers show the lunar date. Habits appear at their reminder time.
- Habit: header `Habit ˅  [list/grid] [+] […]`, a week strip of daily progress rings, habits grouped by section
  as cards (icon, name, ⚡ total days, 🔥 streak, 7 dots for the week).
- Countdown: filter tabs All / Countdown / Anniversary / Birthday / Holiday; centred cards
  ("Days until 04/12/2026" / "Days since …").
- Eisenhower Matrix: four quadrants by priority (Urgent & Important = high, Not urgent & important = medium,
  Urgent & unimportant = low, Not urgent & unimportant = none), each grouped Overdue / dates / No date / Completed.
- Search: a centred overlay "Search tasks, tags, lists and filters".
- Task detail: date popover = Date | Duration tabs, quick picks (today, tomorrow, next week, tonight), month
  with lunar dates, Time ›, Reminder ›, Repeat ›, Clear / OK. "…" menu: Add Subtask, Pin, Won't Do, Tags, Duplicate,
  Copy Link, Convert to Note, Delete (and more not in scope: attachments, templates, focus, sticky note, print).
- Pomodoro / focus records: not in scope here (no data model yet) — a later phase.

## Global Constraints

- All edits through shared actions (`store.update`); new settings are synced Preferences with defaults.
- Lunar dates: Vietnamese lunar calendar (UTC+7), computed in shared code.
- Light/dark snapshots for every changed screen; tokens only; keyboard reachable.

## Tasks

1. **Review minors:** N-a-week habits count as due only until the week's target is met; month rate honours
   every-N-days and days before the habit existed; a non-number amount shows an error instead of vanishing;
   literal offsets become tokens; readable text on light card colours.
2. **Shared:** `Lunar.of(date)` (day, month, leap) with tests (8 Nov 2026 = 30/9, 9 Nov 2026 = 1/10,
   17 Feb 2026 = 1/1); `TaskActions.setRepeat`, `setReminders`, `pin`/`unpin`; `CalendarQueries` takes a
   `CalendarFilter` (lists/tags to show, show completed, show habits, show countdowns) — habits become entries at
   their reminder time (all-day without one) on the days they're due; Preference `calendar.view`.
3. **Task detail:** TickTick-style date popover (quick picks, graph month, time, reminder, repeat, Clear/OK);
   "…" menu (Add subtask, Pin/Unpin, Won't do, Duplicate, Copy link, Move to Trash).
4. **Calendar:** "+" new task on the shown day, "…" menu (View options sheet, Arrange tasks, Calendars…), left bar
   (mini month with lunar dates and dots, show checkboxes for lists/tags/filters/calendars), lunar dates in day
   headers.
5. **Countdown tabs, Search overlay (rail ⌘5), Habit cards with sections and a week ring strip, Eisenhower Matrix
   (new rail area).**
6. **Docs, snapshots, release, hub pointer** — after 1–4 (release), then after 5 (release).

# P6 — Habits and Countdowns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace TickTick fully on the Mac.
- **Habits** (rail ⌘3): check in today, see streaks and a monthly calendar, create and edit habits (daily,
  certain weekdays, or N times a week; yes/no or a number with a goal and unit), archive them.
- **Countdowns** (rail ⌘4): days until or since a date, with yearly repeats for birthdays and
  anniversaries. A countdown can be shown on the calendar.

TickTick's habits, check-ins and countdowns already come in through the P2 import.

**Spec:** §3 (priority 4), §6 (Habit, HabitSection, HabitCheckin, Countdown), §11.

## Global Constraints

- All edits go through `ZenStore.update` / `put(base=)`.
- A check-in's id is deterministic per habit and day (`chk_<habitId>_<yyyyMMdd>`), so two devices that
  check in the same day merge instead of duplicating. An imported TickTick check-in for that day is
  found by `habitId + stamp` first.
- Habit `repeatRule` uses TickTick's format:
  - `RRULE:FREQ=DAILY;INTERVAL=n`
  - `RRULE:FREQ=WEEKLY;BYDAY=MO,WE`
  - `RRULE:FREQ=WEEKLY;TT_TIMES=3`, meaning N times a week on any days
  
  An unknown rule counts as daily.
- Check-in status: 2 = done, 1 = unfinished (a number below its goal), 0 = none.
- Countdown `date` is yyyyMMdd. `repeat = "yearly"` means the next anniversary. Days are counted in the
  Mac's time zone.
- UI per `feedback-zentask-ui-size`: comfortable, colourful, big targets, TickTick interactions.
  Light and dark are both checked.

## Tasks

1. **Shared `habits/`:**
   - `HabitSchedule.isDue(habit, date)`, `weekTarget`
   - `HabitStats`: current and longest streak, total days, monthly rate, done days for a month
   - `HabitActions`: `create`, `update` (via `update`), `archive`/`unarchive`, `delete` (tombstone the
     habit and its check-ins), `checkIn(habitId, date, amount?)` (adds `step` for REAL habits, toggles
     for BOOLEAN), `clear(habitId, date)`
   - Tests: streaks with weekday rules and N-a-week rules, toggling, REAL goals, two-device merge of
     the same day, and the imported check-in being reused.
2. **Shared `countdowns/`:**
   - `CountdownQueries.next(c, today)` gives the days left (negative = since) and the anniversary
     count / age.
   - `CountdownActions`: `create`, `update`, `archive`, `delete`.
   - Calendar: countdowns with `showInCalendar` become all-day entries.
   - Tests: yearly next occurrence across year end, 29 February, days since a past one-off, and the
     calendar entry.
3. **macOS Habits area:**
   - The habit list shows: icon in a coloured circle, name, streak, a 7-day strip you can tap.
   - The detail pane shows a big check-in button, a month heatmap, and stat cards.
   - A sheet creates and edits habits.
   - Tests on the model; snapshots.
4. **macOS Countdowns area:**
   - A card grid shows a big number, "days left" or "days since", the date, and the colour.
   - A sheet creates and edits countdowns.
   - Right-click a card to edit it, archive it, or delete it (with a confirmation).
5. **MCP and release:**
   - MCP tools: `list_habits`, `check_in_habit`, `list_countdowns`, `create_countdown`.
   - Update the docs and the spec status.
   - Deploy, then release 0.6.0.
   - Review, fix what it finds, and bump the hub pointer.

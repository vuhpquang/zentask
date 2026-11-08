# P6d — Focus (Pomodoro), Kanban/Timeline views, Duration, Summary

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** finish TickTick parity on the Mac (owner's choice, 2026-11-08) before P7 Android:
- **Focus** (rail): a Pomodoro timer (25/5 by default, long break every 4) and a stopwatch, optionally linked to a
  task. Records sync like everything else. Overview (today's pomos and focus time, totals) and a record timeline.
  "Start focus" in a task's "…" menu.
- **Kanban view:** a list shows its sections as columns of cards, with drag between columns (sets the section)
  and within a column (order). Timeline view: tasks with dates as bars on a day/week scale.
  Picked from the list "…" menu (View: List / Kanban / Timeline), stored in `Project.viewMode`.
- **Duration tab** in the date popover: start and end date/time (`startDate` + `dueDate`), as TickTick's.
- **Summary** smart view: done and open tasks for a period (today / this week / last week / this month), grouped
  by list, copyable as text.

**Spec:** §6 (data model: new entity `FocusRecord`), §11. TickTick survey notes in the P6c plan.

## Global Constraints

- New entity `FocusRecord` (id, taskId?, start, end, kind "pomo"/"stopwatch", note), client-writable, synced;
  name is a file format. Timer state is local (UserDefaults) so a relaunch resumes a running pomo.
- Notifications when a pomo or break ends (local, like reminders).
- Tokens only, light/dark checked, keyboard: Space starts/pauses on the Focus screen, Esc stops.

## Tasks

1. Shared: `FocusRecord` entity + `FocusActions` (start/stop/record), `FocusStats` (today/total pomos and
   minutes, per-day), tests; `TaskActions.setRange` already exists as `setSpan` (Duration uses it).
2. macOS Focus area: timer ring, Pomo/Stopwatch tabs, task picker, overview cards, record timeline, notifications,
   "Start focus" in the task menu.
3. Kanban view (sections as columns, drag), Timeline view; View picker in the list "…" menu.
4. Duration tab; Summary view (sidebar smart item, period picker, copy).
5. Docs, snapshots, release, review.

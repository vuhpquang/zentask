# P6b — Side panels, list header and collapsible sections

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** match how TickTick's Mac app feels in the three places the owner pointed at (screenshots, 2026-11-08):
1. **Calendar → right panel** ("arrange tasks"): pick any smart list / list / filter from a popover, group by,
   sort by, order, hide completed, hide details; drag tasks onto the calendar.
2. **Tasks → right calendar panel**: a narrow calendar beside the task detail (agenda timeline by default,
   or day grid), with ‹ ○ › and a "…" menu for modes and Close.
3. **Collapsible groups** in every task list, with a smooth animation; list groups carry the list's colour bar.

The task list header becomes TickTick-like everywhere: "☰ <icon> <name>" (opens the list picker), a sort button
(Group by › / Sort by › / Order ›) and a "…" menu (Hide completed, Hide details, Add section).

**Spec:** §11 (macOS UI), feedback memory "ZenTask UI feel" (comfortable, colourful, TickTick interactions).
Item 4 of the request ("test TickTick and copy it fully") is worked from the owner's screenshots: the owner's
TickTick app and account are not driven by the agent.

## Global Constraints

- ZenTask's own look (blue→violet accent, its icons); no TickTick assets or copy.
- View options stay the synced Preference `view.<filterKey>`; new fields decode with defaults (old JSON loads).
- Collapsed groups are per-device UI state (UserDefaults), keyed by filter key + section key.
- Light and dark both checked by snapshot; tokens in `Theme.swift`; animations respect Reduce Motion.

## Tasks

1. **Shared:** `ViewOptions` gains `order` ("" / "asc" / "desc"), `hideCompleted`, `hideDetails`.
   `TaskSection` gains a stable `key` (e.g. `list:<id>`, `col:<id>`, `date:today`, `prio:5`, `done`) and a
   `color` (list colour when grouped by list, priority colour by priority). `order = "desc"` reverses the
   chosen sort inside each group; `hideCompleted` drops the Completed group.
   Tests: keys are stable and unique, desc reverses, hideCompleted, old JSON still parses.
2. **macOS list header + collapsible sections:** `ListHeader` (title button → `ListPicker` popover with
   counts and colours, sort menu, "…" menu); sections become custom headers with a rotating chevron,
   count, and `withAnimation(.snappy)`; list-coloured leading bar on rows of a list group; hide details hides
   the notes line. Model tests for collapse persistence.
3. **Calendar right panel:** replaces the Unscheduled panel with the same header/sections, its own filter and
   options (stored per device), "Only without a date" toggle, Close and a toolbar button to reopen; drag to
   the calendar as before.
4. **Tasks right calendar panel:** toolbar toggle; its own `CalendarModel` (Day or Agenda, default Agenda);
   the agenda redesigned as a timeline (big day number, weekday, times, coloured cards) and used by the main
   Calendar's Agenda mode too; tasks dragged from the list onto the panel's day grid get a time.
5. **Docs, snapshots light/dark, release, hub pointer.**

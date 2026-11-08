import Foundation
import Observation
@preconcurrency import ZenTaskShared

/// State of the Calendar area: view mode, the shown range, and its days (computed off the main thread
/// from the shared core, refreshed when any record changes).
@MainActor
@Observable
final class CalendarModel {
    enum Mode: String, CaseIterable, Identifiable {
        case day = "Day", week = "Week", month = "Month", year = "Year", agenda = "Agenda", multiDay = "Multi-Day", multiWeek = "Multi-Week"
        var id: String { rawValue }

        /// Single-key shortcuts (letter and number), as in the menu.
        var keys: (letter: Character, number: Character)? {
            switch self {
            case .day: ("d", "1")
            case .week: ("w", "2")
            case .month: ("m", "3")
            case .year: ("y", "4")
            case .agenda: ("a", "5")
            default: nil
            }
        }

        var detail: String? {
            switch self {
            case .multiDay: "5 days"
            case .multiWeek: "2 weeks"
            default: nil
            }
        }

        /// Hour-grid modes (the others are day cells or lists).
        var isGrid: Bool { self == .day || self == .week || self == .multiDay }
    }

    let core: ZenTaskCore
    var calendar: Calendar

    var mode: Mode = .week { didSet { _Concurrency.Task { await reload() } } }
    var anchor: Date = .now { didSet { _Concurrency.Task { await reload() } } }
    private(set) var days: [CalendarDay] = []
    private var started = false
    private var generation = 0
    private var pending: _Concurrency.Task<Void, Never>?

    /// Height of one hour in the grid; pinch or ⌘-scroll to zoom.
    var hourHeight: CGFloat = Theme.Calendar.hourHeight
    /// ⌥ held: task blocks show their checkbox.
    var optionDown = false

    func zoom(by factor: CGFloat) {
        hourHeight = min(Theme.Calendar.maxHourHeight, max(Theme.Calendar.minHourHeight, hourHeight * factor))
    }

    /// What to show and how to colour it (synced Preference `calendar.view`).
    private(set) var viewOptions: CalendarViewOptions

    init(core: ZenTaskCore, calendar: Calendar = CalendarModel.mondayFirst()) {
        self.core = core
        self.calendar = calendar
        viewOptions = core.calendarView()
    }

    func setViewOptions(_ o: CalendarViewOptions) {
        core.setCalendarView(options: o)
        viewOptions = o
        _Concurrency.Task { await reload() }
    }

    /// The Vietnamese lunar date for a day ("30", "1/10"), when lunar dates are on.
    func lunar(_ date: Date) -> String? {
        guard viewOptions.showLunar else { return nil }
        return core.lunarLabel(dayMillis: Int64(calendar.startOfDay(for: date).timeIntervalSince1970 * 1000))
    }

    /// Weeks start on Monday (Vietnamese convention; spec P5 ruling).
    static func mondayFirst() -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.firstWeekday = 2
        c.timeZone = .autoupdatingCurrent
        return c
    }

    func start() {
        guard !started else { return }
        started = true
        _Concurrency.Task { await reload() }
        _Concurrency.Task {
            for await _ in core.recordChanges {
                // Coalesce bursts (a sync page) into one reload.
                pending?.cancel()
                pending = _Concurrency.Task {
                    try? await _Concurrency.Task.sleep(for: .milliseconds(120))
                    guard !_Concurrency.Task.isCancelled else { return }
                    await reload()
                }
            }
        }
    }

    /// First day shown and the number of days for the current mode.
    var range: (first: Date, count: Int) {
        let day = calendar.startOfDay(for: anchor)
        switch mode {
        case .day:
            return (day, 1)
        case .week:
            let start = calendar.dateInterval(of: .weekOfYear, for: day)?.start ?? day
            return (start, 7)
        case .month:
            let monthStart = calendar.dateInterval(of: .month, for: day)?.start ?? day
            let gridStart = calendar.dateInterval(of: .weekOfYear, for: monthStart)?.start ?? monthStart
            return (gridStart, 42) // always 6 weeks
        case .multiDay:
            return (day, 5)
        case .multiWeek:
            return (calendar.dateInterval(of: .weekOfYear, for: day)?.start ?? day, 14)
        case .year:
            let start = calendar.dateInterval(of: .year, for: day)?.start ?? day
            let days = calendar.range(of: .day, in: .year, for: day)?.count ?? 365
            return (start, days)
        case .agenda:
            return (day, 30)
        }
    }

    private var latest: _Concurrency.Task<Void, Never>?

    /// Recomputes the shown days. A newer request (mode/date change) wins; every caller returns only
    /// once the newest result is in.
    func reload() async {
        viewOptions = core.calendarView()
        generation += 1
        let mine = generation
        let (first, count) = range
        let core = self.core
        let millis = Int64(first.timeIntervalSince1970 * 1000)
        let task = _Concurrency.Task {
            let result = await _Concurrency.Task.detached(priority: .userInitiated) { core.calendarDays(firstDayMillis: millis, count: Int32(count)) }.value
            if mine == self.generation { self.days = result }
        }
        latest = task
        await task.value
        while let newest = latest, mine != generation {
            await newest.value
            if newest == latest { break }
        }
    }

    func next() { anchor = step(1) }
    func previous() { anchor = step(-1) }
    func today() { anchor = .now }

    private func step(_ n: Int) -> Date {
        switch mode {
        case .day: calendar.date(byAdding: .day, value: n, to: anchor) ?? anchor
        case .multiDay: calendar.date(byAdding: .day, value: 5 * n, to: anchor) ?? anchor
        case .week, .agenda: calendar.date(byAdding: .weekOfYear, value: n, to: anchor) ?? anchor
        case .multiWeek: calendar.date(byAdding: .weekOfYear, value: 2 * n, to: anchor) ?? anchor
        case .month: calendar.date(byAdding: .month, value: n, to: anchor) ?? anchor
        case .year: calendar.date(byAdding: .year, value: n, to: anchor) ?? anchor
        }
    }

    var title: String {
        let (first, count) = range
        switch mode {
        case .day: return first.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
        case .month: return anchor.formatted(.dateTime.month(.wide).year())
        case .year: return anchor.formatted(.dateTime.year())
        case .week, .multiDay, .multiWeek, .agenda:
            let last = calendar.date(byAdding: .day, value: count - 1, to: first) ?? first
            return first.formatted(.dateTime.day().month(.abbreviated)) + " – " + last.formatted(.dateTime.day().month(.abbreviated).year())
        }
    }

    func date(of day: CalendarDay) -> Date { Date(timeIntervalSince1970: Double(day.dayStart) / 1000) }

    /// Schedules a dropped task: [minute] since midnight on [day], snapped to 15 minutes; nil = all day.
    /// Ids that name a live task (drops of events or foreign text are ignored).
    func liveTaskIds(_ ids: [String]) -> [String] {
        ids.filter { id in core.task(id: id).map { !$0.deleted && $0.trashedAt == nil } ?? false }
    }

    func schedule(taskId: String, on day: CalendarDay, minute: Int?) {
        let start = date(of: day)
        if let minute {
            let snapped = max(0, min(24 * 60 - Theme.Calendar.snapMinutes, (minute / Theme.Calendar.snapMinutes) * Theme.Calendar.snapMinutes))
            let when = start.addingTimeInterval(Double(snapped) * 60)
            core.actions.setDue(id: taskId, due: KotlinLong(value: Int64(when.timeIntervalSince1970 * 1000)), allDay: false)
        } else {
            core.actions.setDue(id: taskId, due: KotlinLong(value: Int64(start.timeIntervalSince1970 * 1000)), allDay: true)
        }
    }
}

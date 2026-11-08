import Foundation
import Observation
@preconcurrency import ZenTaskShared

/// State of the Calendar area: view mode, the shown range, and its days (computed off the main thread
/// from the shared core, refreshed when any record changes).
@MainActor
@Observable
final class CalendarModel {
    enum Mode: String, CaseIterable, Identifiable {
        case day = "Day", week = "Week", month = "Month"
        var id: String { rawValue }
    }

    let core: ZenTaskCore
    var calendar: Calendar

    var mode: Mode = .week { didSet { _Concurrency.Task { await reload() } } }
    var anchor: Date = .now { didSet { _Concurrency.Task { await reload() } } }
    private(set) var days: [CalendarDay] = []
    private var started = false

    init(core: ZenTaskCore, calendar: Calendar = CalendarModel.mondayFirst()) {
        self.core = core
        self.calendar = calendar
    }

    /// Weeks start on Monday (Vietnamese convention; spec P5 ruling).
    static func mondayFirst() -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.firstWeekday = 2
        c.timeZone = .current
        return c
    }

    func start() {
        guard !started else { return }
        started = true
        _Concurrency.Task { await reload() }
        _Concurrency.Task { for await _ in core.recordChanges { await reload() } }
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
        }
    }

    func reload() async {
        let (first, count) = range
        let core = self.core
        let millis = Int64(first.timeIntervalSince1970 * 1000)
        let result = await _Concurrency.Task.detached(priority: .userInitiated) { core.calendarDays(firstDayMillis: millis, count: Int32(count)) }.value
        if range.first == first { days = result }
    }

    func next() { anchor = step(1) }
    func previous() { anchor = step(-1) }
    func today() { anchor = .now }

    private func step(_ n: Int) -> Date {
        switch mode {
        case .day: calendar.date(byAdding: .day, value: n, to: anchor) ?? anchor
        case .week: calendar.date(byAdding: .weekOfYear, value: n, to: anchor) ?? anchor
        case .month: calendar.date(byAdding: .month, value: n, to: anchor) ?? anchor
        }
    }

    var title: String {
        let (first, count) = range
        switch mode {
        case .day: return first.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
        case .month: return anchor.formatted(.dateTime.month(.wide).year())
        case .week:
            let last = calendar.date(byAdding: .day, value: count - 1, to: first) ?? first
            return first.formatted(.dateTime.day().month(.abbreviated)) + " – " + last.formatted(.dateTime.day().month(.abbreviated).year())
        }
    }

    func date(of day: CalendarDay) -> Date { Date(timeIntervalSince1970: Double(day.dayStart) / 1000) }

    /// Schedules a dropped task: [minute] since midnight on [day], snapped to 15 minutes; nil = all day.
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

import Foundation
import Observation
@preconcurrency import ZenTaskShared

/// What the habit sheet shows.
enum HabitSheet: Identifiable, Hashable {
    case new
    case edit(String)
    var id: String { if case .edit(let id) = self { "edit:\(id)" } else { "new" } }
}

/// State of the Habits area: every habit with its stats (built off the main thread by the shared
/// core, refreshed when any record changes), the selection and the month shown in the detail.
@MainActor
@Observable
final class HabitsModel {
    let core: ZenTaskCore
    let calendar: Calendar

    private(set) var rows: [HabitRow] = []
    private(set) var today: Int32 = 0
    var selection: String?
    var sheet: HabitSheet?
    var confirmDelete: Habit?
    var showArchived = false
    /// Any day in the month the detail heatmap shows.
    var month: Date = .now

    private var started = false
    private var pending: _Concurrency.Task<Void, Never>?

    init(core: ZenTaskCore, calendar: Calendar = CalendarModel.mondayFirst()) {
        self.core = core
        self.calendar = calendar
    }

    func start() {
        guard !started else { return }
        started = true
        _Concurrency.Task { await reload() }
        // "Today" moves at midnight (or with a time-zone change) even when no record changes.
        _Concurrency.Task {
            for await _ in NotificationCenter.default.notifications(named: .NSCalendarDayChanged) { await reload() }
        }
        _Concurrency.Task {
            for await _ in core.recordChanges {
                pending?.cancel()
                pending = _Concurrency.Task {
                    try? await _Concurrency.Task.sleep(for: .milliseconds(80))
                    guard !_Concurrency.Task.isCancelled else { return }
                    await reload()
                }
            }
        }
    }

    func reload() async {
        let core = self.core
        let (rows, today) = await _Concurrency.Task.detached(priority: .userInitiated) { (core.habitRows(), core.todayStamp()) }.value
        self.rows = rows
        self.today = today
        if selection == nil || !rows.contains(where: { $0.habit.id == selection }) {
            selection = active.first?.habit.id
        }
    }

    var active: [HabitRow] { rows.filter { !$0.archived } }
    var archived: [HabitRow] { rows.filter(\.archived) }
    var selected: HabitRow? { rows.first { $0.habit.id == selection } }

    /// ↑/↓ in the list: the next or previous visible habit.
    func moveSelection(_ by: Int) {
        let ids = (active + (showArchived ? archived : [])).map(\.habit.id)
        guard !ids.isEmpty else { return }
        let i = selection.flatMap { ids.firstIndex(of: $0) } ?? -by
        selection = ids[max(0, min(ids.count - 1, i + by))]
    }

    // MARK: Days

    func stamp(_ date: Date) -> Int32 {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return Int32((c.year ?? 0) * 10_000 + (c.month ?? 0) * 100 + (c.day ?? 0))
    }

    func date(_ stamp: Int32) -> Date {
        calendar.date(from: DateComponents(year: Int(stamp / 10_000), month: Int(stamp / 100 % 100), day: Int(stamp % 100))) ?? .now
    }

    /// The last [count] days ending today, oldest first.
    func lastDays(_ count: Int = 7) -> [Date] {
        let end = date(today == 0 ? stamp(.now) : today)
        return (0..<count).reversed().compactMap { calendar.date(byAdding: .day, value: -$0, to: end) }
    }

    /// The shown month as weeks of 7 cells (nil = padding before the 1st / after the last day).
    func monthGrid() -> [[Date?]] {
        guard let interval = calendar.dateInterval(of: .month, for: month) else { return [] }
        let first = interval.start
        let lead = (calendar.component(.weekday, from: first) - calendar.firstWeekday + 7) % 7
        let days = calendar.range(of: .day, in: .month, for: first)?.count ?? 30
        var cells: [Date?] = Array(repeating: nil, count: lead)
        cells += (0..<days).map { calendar.date(byAdding: .day, value: $0, to: first) }
        while cells.count % 7 != 0 { cells.append(nil) }
        return stride(from: 0, to: cells.count, by: 7).map { Array(cells[$0..<$0 + 7]) }
    }

    func isDone(_ row: HabitRow, _ day: Date) -> Bool { row.doneStamps.contains(KotlinInt(value: stamp(day))) }

    func value(_ row: HabitRow, _ day: Date) -> Double { row.values[KotlinInt(value: stamp(day))]?.doubleValue ?? 0 }

    func isFuture(_ day: Date) -> Bool { stamp(day) > today }

    /// Done days in the shown month and the share of its scheduled days so far (0…1). Days before the habit
    /// existed don't count; every-N-days habits only count their own days.
    func monthStats(_ row: HabitRow) -> (done: Int, rate: Double) {
        let draft = HabitDraft(habit: row.habit)
        let created = row.habit.createdAt > 0 ? calendar.startOfDay(for: Date(timeIntervalSince1970: Double(row.habit.createdAt) / 1000)) : .distantPast
        let anchor = row.habit.targetStartDate.map { date($0.int32Value) } ?? created
        let days = monthGrid().flatMap { $0 }.compactMap { $0 }.filter { !isFuture($0) && $0 >= created }
        let done = days.filter { isDone(row, $0) }.count
        let due: Double
        switch draft.frequency {
        case .daily:
            if draft.interval > 1, anchor != .distantPast {
                due = Double(days.filter { (calendar.dateComponents([.day], from: anchor, to: $0).day ?? 0) % draft.interval == 0 }.count)
            } else {
                due = Double(days.count)
            }
        case .weekdays(let set): due = Double(days.filter { set.contains(calendar.component(.weekday, from: $0)) }.count)
        case .timesAWeek(let n): due = (Double(days.count) / 7 * Double(n)).rounded(.up)
        }
        return (done, due > 0 ? min(1, Double(done) / due) : 0)
    }

    // MARK: Actions

    /// Yes/no: toggles the day. Number: adds the habit's step. Future days are ignored.
    func tap(_ row: HabitRow, on day: Date) {
        guard !isFuture(day) else { return }
        core.habitCheckIn(habitId: row.habit.id, stamp: stamp(day))
    }

    func add(_ row: HabitRow, amount: Double, on day: Date) {
        guard !isFuture(day), amount > 0 else { return }
        core.habitAdd(habitId: row.habit.id, stamp: stamp(day), amount: amount)
    }

    func clear(_ row: HabitRow, on day: Date) {
        core.habitClear(habitId: row.habit.id, stamp: stamp(day))
    }

    /// [original] = the habit as the editor opened it (nil = new): only what the editor changed is written.
    func save(_ draft: HabitDraft, original: Habit?) {
        let a = core.habitActions
        if let original {
            a.update(original: original, name: draft.name, icon: draft.storedIcon, color: draft.color, type: draft.type,
                     goal: draft.goal, step: draft.step, unit: draft.unit, repeatRule: draft.repeatRule)
        } else {
            let h = a.create(name: draft.name, icon: draft.storedIcon, color: draft.color, type: draft.type,
                             goal: draft.goal, step: draft.step, unit: draft.unit, repeatRule: draft.repeatRule)
            selection = h.id
        }
    }

    func archive(_ habit: Habit) {
        if habit.archivedAt == nil { core.habitActions.archive(id: habit.id) } else { core.habitActions.unarchive(id: habit.id) }
    }

    func delete(_ habit: Habit) {
        core.habitActions.delete(id: habit.id)
        if selection == habit.id { selection = nil }
    }
}

/// The editable fields of a habit, with the repeat rule in TickTick's format.
struct HabitDraft: Equatable {
    enum Frequency: Equatable {
        case daily
        /// Calendar weekdays (1 = Sunday … 7 = Saturday).
        case weekdays(Set<Int>)
        case timesAWeek(Int)
    }

    var name = ""
    var icon = ""
    var color: String? = Palette.colors[0]
    var isNumber = false
    var goal: Double = 1
    var step: Double = 1
    var unit = ""
    var frequency: Frequency = .daily
    /// "Every N days" (from INTERVAL); the editor keeps such rules as they are.
    private(set) var interval = 1
    /// What the habit had when the editor opened: kept unless the user changes that part
    /// (TickTick icon names and rules the editor can't show, like every 2 days, survive a rename).
    private var originalRule: String?
    private var originalFrequency: Frequency?
    private var originalIcon: String?
    private var originalIconText = ""

    private static let codes = [1: "SU", 2: "MO", 3: "TU", 4: "WE", 5: "TH", 6: "FR", 7: "SA"]

    init() {}

    init(habit: Habit) {
        name = habit.name
        icon = HabitLook.emoji(habit.icon) ?? ""
        originalIcon = habit.icon
        originalIconText = icon
        originalRule = habit.repeatRule
        color = habit.color
        isNumber = habit.type == Habit.companion.TYPE_REAL
        goal = habit.goal
        step = habit.step
        unit = habit.unit
        let parts = Dictionary((habit.repeatRule ?? "").replacingOccurrences(of: "RRULE:", with: "").split(separator: ";").compactMap { p -> (String, String)? in
            let kv = p.split(separator: "=", maxSplits: 1)
            return kv.count == 2 ? (kv[0].uppercased(), String(kv[1])) : nil
        }, uniquingKeysWith: { a, _ in a })
        interval = parts["INTERVAL"].flatMap(Int.init).map { max(1, $0) } ?? 1
        if let n = parts["TT_TIMES"].flatMap(Int.init), n > 0 {
            frequency = .timesAWeek(n)
        } else if parts["FREQ"] == "WEEKLY", let days = parts["BYDAY"] {
            let set = Set(days.split(separator: ",").compactMap { code in Self.codes.first { $0.value == code.uppercased() }?.key })
            frequency = set.isEmpty || set.count == 7 ? .daily : .weekdays(set)
        }
        originalFrequency = frequency
    }

    /// The icon to store: the original one (even a TickTick icon name) unless the field was edited.
    var storedIcon: String? {
        let typed = icon.trimmingCharacters(in: .whitespaces)
        if originalFrequency != nil, typed == originalIconText { return originalIcon }
        return typed.isEmpty ? nil : typed
    }

    var type: String { isNumber ? Habit.companion.TYPE_REAL : Habit.companion.TYPE_BOOLEAN }

    var repeatRule: String {
        if let originalRule, frequency == originalFrequency { return originalRule }
        return switch frequency {
        case .daily: "RRULE:FREQ=DAILY;INTERVAL=1"
        case .weekdays(let set): "RRULE:FREQ=WEEKLY;BYDAY=" + [2, 3, 4, 5, 6, 7, 1].filter(set.contains).compactMap { Self.codes[$0] }.joined(separator: ",")
        case .timesAWeek(let n): "RRULE:FREQ=WEEKLY;TT_TIMES=\(n)"
        }
    }

    var isValid: Bool {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        if case .weekdays(let set) = frequency, set.isEmpty { return false }
        return !isNumber || (goal > 0 && step > 0)
    }
}

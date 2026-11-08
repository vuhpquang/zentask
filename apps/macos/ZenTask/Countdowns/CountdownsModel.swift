import Foundation
import Observation
@preconcurrency import ZenTaskShared

/// What the countdown sheet shows.
enum CountdownSheet: Identifiable, Hashable {
    case new
    case edit(String)
    var id: String { if case .edit(let id) = self { "edit:\(id)" } else { "new" } }
}

/// State of the Countdown area: every countdown as a card (soonest first), refreshed on record changes.
@MainActor
@Observable
final class CountdownsModel {
    let core: ZenTaskCore
    let calendar: Calendar

    private(set) var cards: [CountdownCard] = []
    var sheet: CountdownSheet?
    var confirmDelete: Countdown?
    var showArchived = false

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
        cards = await _Concurrency.Task.detached(priority: .userInitiated) { core.countdownCards() }.value
    }

    var active: [CountdownCard] { cards.filter { $0.countdown.archivedAt == nil } }
    var archived: [CountdownCard] { cards.filter { $0.countdown.archivedAt != nil } }

    func save(_ draft: CountdownDraft, editing id: String?) {
        let a = core.countdownActions
        let icon = draft.icon.trimmingCharacters(in: .whitespaces)
        let stamp = draft.stamp(calendar)
        if let id {
            a.update(id: id, name: draft.name, date: stamp, type: draft.type, repeat: draft.yearly ? "yearly" : "none",
                     color: draft.color, icon: icon.isEmpty ? nil : icon, note: draft.note, showInCalendar: draft.showInCalendar)
        } else {
            _ = a.create(name: draft.name, date: stamp, type: draft.type, repeat: draft.yearly ? "yearly" : "none",
                         color: draft.color, icon: icon.isEmpty ? nil : icon, note: draft.note, showInCalendar: draft.showInCalendar)
        }
    }

    func archive(_ c: Countdown) {
        if c.archivedAt == nil { core.countdownActions.archive(id: c.id) } else { core.countdownActions.unarchive(id: c.id) }
    }

    func delete(_ c: Countdown) { core.countdownActions.delete(id: c.id) }
}

/// The editable fields of a countdown.
struct CountdownDraft: Equatable {
    static let types: [(id: String, title: String, symbol: String)] = [
        ("COUNTDOWN", "Countdown", "hourglass"),
        ("BIRTHDAY", "Birthday", "birthday.cake.fill"),
        ("ANNIVERSARY", "Anniversary", "heart.fill"),
        ("HOLIDAY", "Holiday", "sun.max.fill"),
    ]

    var name = ""
    var date = Date.now
    var type = "COUNTDOWN"
    var yearly = false
    var color: String? = Palette.colors[2]
    var icon = ""
    var note = ""
    var showInCalendar = true

    init() {}

    init(_ c: Countdown, calendar: Calendar) {
        name = c.name
        let s = Int(c.date)
        date = calendar.date(from: DateComponents(year: s / 10_000, month: s / 100 % 100, day: s % 100)) ?? .now
        type = c.type
        yearly = c.`repeat` == "yearly"
        color = c.color
        icon = HabitLook.emoji(c.icon) ?? ""
        note = c.note
        showInCalendar = c.showInCalendar
    }

    func stamp(_ calendar: Calendar) -> Int32 {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return Int32((c.year ?? 0) * 10_000 + (c.month ?? 0) * 100 + (c.day ?? 0))
    }

    var isValid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }
}

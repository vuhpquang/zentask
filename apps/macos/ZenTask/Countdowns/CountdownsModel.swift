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
    /// Type filter: "" = all, or COUNTDOWN / BIRTHDAY / ANNIVERSARY / HOLIDAY.
    var typeFilter = ""

    private var started = false
    private var pending: _Concurrency.Task<Void, Never>?
    /// Whether the screen showing this model is on screen (set by the view).
    @ObservationIgnored private var visible = false
    @ObservationIgnored private var stale = false

    func setVisible(_ on: Bool) {
        visible = on
        if on && stale {
            stale = false
            _Concurrency.Task { await reload() }
        }
    }

    init(core: ZenTaskCore, calendar: Calendar = CalendarModel.mondayFirst()) {
        self.core = core
        self.calendar = calendar
    }

    func start() {
        guard !started else { return }
        started = true
        _Concurrency.Task { await reload() }
        _Concurrency.Task {
            for await _ in NotificationCenter.default.notifications(named: .NSCalendarDayChanged) { await reload() }
        }
        _Concurrency.Task {
            for await _ in core.recordChanges {
                // Hidden screens only note that they're stale and reload when shown.
                guard visible else { stale = true; continue }
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

    private var shown: [CountdownCard] { typeFilter.isEmpty ? cards : cards.filter { $0.countdown.type == typeFilter } }
    var active: [CountdownCard] { shown.filter { $0.countdown.archivedAt == nil } }
    var archived: [CountdownCard] { shown.filter { $0.countdown.archivedAt != nil } }

    /// [original] = the countdown as the editor opened it (nil = new): only what the editor changed is written.
    func save(_ draft: CountdownDraft, original: Countdown?) {
        let a = core.countdownActions
        let stamp = draft.stamp(calendar)
        if let original {
            a.update(original: original, name: draft.name, date: stamp, type: draft.type, repeat: draft.yearly ? "yearly" : "none",
                     color: draft.color, icon: draft.storedIcon, note: draft.note, showInCalendar: draft.showInCalendar)
        } else {
            _ = a.create(name: draft.name, date: stamp, type: draft.type, repeat: draft.yearly ? "yearly" : "none",
                         color: draft.color, icon: draft.storedIcon, note: draft.note, showInCalendar: draft.showInCalendar)
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
    private var originalIcon: String?
    private var originalIconText: String?

    init() {}

    init(_ c: Countdown, calendar: Calendar) {
        name = c.name
        let s = Int(c.date)
        date = calendar.date(from: DateComponents(year: s / 10_000, month: s / 100 % 100, day: s % 100)) ?? .now
        type = c.type
        yearly = c.`repeat` == "yearly"
        color = c.color
        icon = HabitLook.emoji(c.icon) ?? ""
        originalIcon = c.icon
        originalIconText = icon
        note = c.note
        showInCalendar = c.showInCalendar
    }

    func stamp(_ calendar: Calendar) -> Int32 {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return Int32((c.year ?? 0) * 10_000 + (c.month ?? 0) * 100 + (c.day ?? 0))
    }

    var isValid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }

    /// The icon to store: the original one unless the field was edited.
    var storedIcon: String? {
        let typed = icon.trimmingCharacters(in: .whitespaces)
        if let originalIconText, typed == originalIconText { return originalIcon }
        return typed.isEmpty ? nil : typed
    }
}

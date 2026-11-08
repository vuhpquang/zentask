import Foundation
import Observation
import UserNotifications
@preconcurrency import ZenTaskShared

/// The Focus timer: Pomodoro (focus, then a short or long break) or a stopwatch, optionally on a task.
/// The running state is saved on this Mac so a relaunch picks it up; finished sessions are synced records.
@MainActor
@Observable
final class FocusModel {
    enum Mode: String { case pomo, stopwatch }
    enum Phase: String { case idle, focus, paused, rest }

    let core: ZenTaskCore
    private let defaults: UserDefaults

    var mode: Mode = .pomo { didSet { if phase == .idle { save() } } }
    private(set) var phase: Phase = .idle
    var taskId: String? { didSet { save() } }
    /// When the current focus (or break) started, and the time already counted before a pause.
    private(set) var startedAt: Date?
    private(set) var banked: TimeInterval = 0
    /// The first start of this session (the record's start).
    private(set) var sessionStart: Date?
    private(set) var pomosInRun = 0
    private(set) var stats: FocusStats?
    var plan = PomodoroPlan(focusMinutes: 25, shortBreak: 5, longBreak: 15, longEvery: 4)

    private var ticker: _Concurrency.Task<Void, Never>?
    /// True while restore() sets properties one by one (their didSet must not save a half-restored state).
    @ObservationIgnored private var restoring = false
    private var started = false
    @ObservationIgnored private var visible = false
    @ObservationIgnored private var stale = false

    init(core: ZenTaskCore, defaults: UserDefaults = .standard) {
        self.core = core
        self.defaults = defaults
        restore()
    }

    func start() {
        guard !started else { return }
        started = true
        _Concurrency.Task { await reload() }
        _Concurrency.Task {
            for await _ in core.recordChanges {
                guard visible else { stale = true; continue }
                await reload()
            }
        }
        tick()
    }

    func setVisible(_ on: Bool) {
        visible = on
        if on && stale { stale = false; _Concurrency.Task { await reload() } }
    }

    func reload() async {
        let core = self.core
        stats = await _Concurrency.Task.detached(priority: .userInitiated) { core.focusStats() }.value
    }

    // MARK: Time

    /// Seconds counted in the current phase (focus or break).
    func elapsed(at now: Date = .now) -> TimeInterval {
        banked + (startedAt.map { now.timeIntervalSince($0) } ?? 0)
    }

    /// Length of the current phase in seconds (nil for the stopwatch).
    var phaseLength: TimeInterval? {
        switch (mode, phase) {
        case (.stopwatch, _): nil
        case (_, .rest): TimeInterval(plan.breakAfter(n: Int32(max(pomosInRun, 1))) * 60)
        default: TimeInterval(plan.focusMinutes * 60)
        }
    }

    /// What the ring shows: time left (Pomodoro) or time counted (stopwatch).
    func display(at now: Date = .now) -> TimeInterval {
        guard let length = phaseLength else { return elapsed(at: now) }
        return max(0, length - elapsed(at: now))
    }

    func progress(at now: Date = .now) -> Double {
        guard let length = phaseLength, length > 0 else { return 0 }
        return min(1, elapsed(at: now) / length)
    }

    // MARK: Controls

    func begin(task: String? = nil) {
        if let task { taskId = task }
        // A new day starts a new run of pomos (the long break comes after every fourth of the day).
        if let last = sessionStart ?? startedAt, !Calendar.current.isDateInToday(last) { pomosInRun = 0 }
        if !Calendar.current.isDateInToday(Date(timeIntervalSince1970: defaults.double(forKey: "focus.runDay"))) { pomosInRun = 0 }
        defaults.set(Date.now.timeIntervalSince1970, forKey: "focus.runDay")
        phase = .focus
        startedAt = .now
        sessionStart = .now
        banked = 0
        scheduleAlert()
        save()
        tick()
    }

    func pause() {
        guard phase == .focus, let s = startedAt else { return }
        banked += Date.now.timeIntervalSince(s)
        startedAt = nil
        if phase == .focus { phase = .paused }
        cancelAlert()
        save()
    }

    func resume() {
        guard phase == .paused else { return }
        phase = .focus
        startedAt = .now
        scheduleAlert()
        save()
        tick()
    }

    /// Stops: a stopwatch session is saved; an unfinished pomo is dropped (as TickTick does); a break ends.
    func stop() {
        if mode == .stopwatch, phase == .focus || phase == .paused, let s = sessionStart {
            _ = core.focusActions.recordOrNull(start: millis(s), end: millis(s.addingTimeInterval(elapsed())), kind: FocusRecord.companion.KIND_STOPWATCH, taskId: taskId, note: "")
        }
        reset()
    }

    /// Skips the rest of a break.
    func skipBreak() {
        guard phase == .rest else { return }
        reset()
    }

    private func reset() {
        phase = .idle
        startedAt = nil
        sessionStart = nil
        banked = 0
        cancelAlert()
        save()
    }

    /// Called every second while running: finishes a pomo or a break when its time is up.
    private func tick() {
        ticker?.cancel()
        guard phase == .focus || phase == .rest, mode == .pomo else { return }
        ticker = _Concurrency.Task { [weak self] in
            while !_Concurrency.Task.isCancelled {
                try? await _Concurrency.Task.sleep(for: .seconds(1))
                guard let self else { return }
                self.check()
            }
        }
    }

    private func check(now: Date = .now) {
        guard mode == .pomo, let length = phaseLength, elapsed(at: now) >= length else { return }
        // When the phase really ended: paused time doesn't count, so it's `now` minus the overshoot.
        let phaseEnd = now.addingTimeInterval(-(elapsed(at: now) - length))
        if phase == .focus, let s = sessionStart {
            _ = core.focusActions.record(start: millis(s), end: millis(phaseEnd), kind: FocusRecord.companion.KIND_POMO, taskId: taskId, note: "")
            pomosInRun += 1
            phase = .rest
            startedAt = phaseEnd
            banked = 0
            scheduleAlert()
            save()
        } else if phase == .rest {
            reset()
            ticker?.cancel()
        }
    }

    private func millis(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }

    // MARK: Alerts

    private func scheduleAlert() {
        cancelAlert()
        guard mode == .pomo, let length = phaseLength else { return }
        let left = length - elapsed()
        guard left > 1 else { return }
        let content = UNMutableNotificationContent()
        content.title = phase == .rest ? "Break's over" : "Focus done"
        content.body = phase == .rest ? "Ready for the next pomo?" : "Time for a \(plan.breakAfter(n: Int32(pomosInRun + 1)))-minute break."
        content.sound = .default
        let request = UNNotificationRequest(identifier: "zt.focus", content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: left, repeats: false))
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    private func cancelAlert() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["zt.focus"])
    }

    // MARK: Saved state

    private func save() {
        guard !restoring else { return }
        let state: [String: Any] = [
            "mode": mode.rawValue, "phase": phase.rawValue, "task": taskId ?? "",
            "startedAt": startedAt?.timeIntervalSince1970 ?? 0, "sessionStart": sessionStart?.timeIntervalSince1970 ?? 0,
            "banked": banked, "pomos": pomosInRun,
        ]
        defaults.set(state, forKey: "focus.state")
    }

    private func restore() {
        guard let s = defaults.dictionary(forKey: "focus.state") else { return }
        restoring = true
        defer { restoring = false }
        mode = Mode(rawValue: s["mode"] as? String ?? "") ?? .pomo
        phase = Phase(rawValue: s["phase"] as? String ?? "") ?? .idle
        taskId = (s["task"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        startedAt = (s["startedAt"] as? Double).flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil }
        sessionStart = (s["sessionStart"] as? Double).flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil }
        banked = s["banked"] as? Double ?? 0
        pomosInRun = s["pomos"] as? Int ?? 0
        restoring = false
        check() // a pomo that ended while the app was closed is recorded now
    }
}

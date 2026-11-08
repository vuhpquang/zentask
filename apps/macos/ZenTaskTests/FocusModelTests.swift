import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class FocusModelTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "zt.focus.tests.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    private func state(mode: String, phase: String, minutesAgo: Double, task: String = "") -> [String: Any] {
        let t = Date.now.addingTimeInterval(-minutesAgo * 60).timeIntervalSince1970
        return ["mode": mode, "phase": phase, "task": task, "startedAt": t, "sessionStart": t, "banked": 0.0, "pomos": 0]
    }

    func testAPomoThatEndedWhileClosedIsRecordedAndTheBreakStarts() async throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let d = defaults()
        d.set(state(mode: "pomo", phase: "focus", minutesAgo: 26, task: "t1"), forKey: "focus.state")
        let m = FocusModel(core: core, defaults: d)
        XCTAssertEqual(m.phase, .rest)
        XCTAssertEqual(m.pomosInRun, 1)
        await m.reload()
        XCTAssertEqual(m.stats?.todayPomos, 1)
        XCTAssertEqual(m.stats?.days.first?.records.first?.taskId, "t1")
        XCTAssertEqual(m.stats?.todayMinutes, 25)
    }

    func testStoppingAStopwatchSavesItAndAnUnfinishedPomoIsDropped() async throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let d = defaults()
        d.set(state(mode: "stopwatch", phase: "focus", minutesAgo: 5), forKey: "focus.state")
        let watch = FocusModel(core: core, defaults: d)
        watch.stop()
        XCTAssertEqual(watch.phase, .idle)
        d.set(state(mode: "pomo", phase: "focus", minutesAgo: 10), forKey: "focus.state")
        let pomo = FocusModel(core: core, defaults: d)
        XCTAssertEqual(pomo.phase, .focus)
        pomo.stop()
        await pomo.reload()
        XCTAssertEqual(pomo.stats?.todayPomos, 0, "an unfinished pomo isn't counted")
        XCTAssertEqual(pomo.stats?.todayMinutes, 5, "the stopwatch's 5 minutes are")
    }

    func testPauseBanksTimeAndResumeContinues() {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let d = defaults()
        d.set(state(mode: "pomo", phase: "focus", minutesAgo: 3), forKey: "focus.state")
        let m = FocusModel(core: core, defaults: d)
        m.pause()
        XCTAssertEqual(m.phase, .paused)
        let left = m.display()
        XCTAssertEqual(left, 22 * 60, accuracy: 2)
        XCTAssertEqual(m.display(at: .now.addingTimeInterval(600)), left, accuracy: 0.01, "paused time doesn't run")
        m.resume()
        XCTAssertEqual(m.phase, .focus)
        XCTAssertEqual(FocusView.clock(1500), "25:00")
        XCTAssertEqual(FocusView.clock(3725), "1:02:05")
    }

    func testRelaunchingTwiceKeepsTheRunningPomo() {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let d = defaults()
        d.set(state(mode: "pomo", phase: "focus", minutesAgo: 3), forKey: "focus.state")
        _ = FocusModel(core: core, defaults: d) // first relaunch (used to save a half-restored state)
        let second = FocusModel(core: core, defaults: d)
        XCTAssertEqual(second.phase, .focus)
        XCTAssertEqual(second.elapsed(), 180, accuracy: 3, "still 3 minutes in")
    }

    func testAPausedPomoEndsLaterAndItsBreakIsWhole() async throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let d = defaults()
        // Started 40 minutes ago, paused for 10, resumed 20 minutes ago: 30 minutes counted, so it ended 5 minutes ago.
        let now = Date.now.timeIntervalSince1970
        d.set(["mode": "pomo", "phase": "focus", "task": "", "startedAt": now - 20 * 60, "sessionStart": now - 40 * 60,
               "banked": 10.0 * 60, "pomos": 0] as [String: Any], forKey: "focus.state")
        let m = FocusModel(core: core, defaults: d)
        XCTAssertEqual(m.phase, .rest)
        XCTAssertEqual(m.elapsed(), 5 * 60, accuracy: 3, "the break started when the pomo really ended")
        await m.reload()
        let record = try XCTUnwrap(m.stats?.days.first?.records.first)
        XCTAssertEqual(Double(record.end) / 1000, now - 5 * 60, accuracy: 3)
    }
}

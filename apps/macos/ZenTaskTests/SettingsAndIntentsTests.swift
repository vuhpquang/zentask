import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class SettingsAndIntentsTests: XCTestCase {
    private let keys = ["quickadd.dates", "calendar.weekStart", "features.matrix"]
    private var saved: [Any?] = []

    override func setUp() async throws {
        saved = keys.map { UserDefaults.standard.object(forKey: $0) }
    }

    override func tearDown() async throws {
        for (k, v) in zip(keys, saved) { UserDefaults.standard.set(v, forKey: k) }
        IntentBridge.tasks = nil
    }

    func testShortcutsAddTasksOnePerLineWithQuickAddAndToday() async throws {
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        await m.reload()
        IntentBridge.tasks = m
        let added = try IntentBridge.add(lines: ["Buy milk #home", "", "Call bank !high"], today: true)
        XCTAssertEqual(added, ["Buy milk", "Call bank"])
        await m.reload()
        let tasks = m.index?.allTasks ?? []
        XCTAssertEqual(tasks.first { $0.title == "Buy milk" }?.tags, ["home"])
        XCTAssertEqual(tasks.first { $0.title == "Call bank" }?.priority, 5)
        XCTAssertTrue(tasks.allSatisfy { $0.dueDate != nil }, "dated today")
        XCTAssertThrowsError(try IntentBridge.add(lines: ["  "], today: false))
    }

    func testDateRecognitionCanBeTurnedOff() async throws {
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        await m.reload()
        UserDefaults.standard.set(true, forKey: "quickadd.dates")
        XCTAssertNotNil(m.parse("Gym tomorrow").due)
        UserDefaults.standard.set(false, forKey: "quickadd.dates")
        let q = m.parse("Gym tomorrow #health")
        XCTAssertNil(q.due)
        XCTAssertEqual(q.title, "Gym tomorrow")
        XCTAssertEqual(q.tags, ["health"], "tags still work")
    }

    func testWeekStartAndHiddenAreas() {
        UserDefaults.standard.set(1, forKey: "calendar.weekStart")
        XCTAssertEqual(CalendarModel.mondayFirst().firstWeekday, 1)
        UserDefaults.standard.set(99, forKey: "calendar.weekStart")
        XCTAssertEqual(CalendarModel.mondayFirst().firstWeekday, 2, "unknown values fall back to Monday")
        UserDefaults.standard.set(false, forKey: "features.matrix")
        XCTAssertFalse(RailTab.matrix.isShown)
        XCTAssertTrue(RailTab.tasks.isShown, "Tasks can't be hidden")
    }
}

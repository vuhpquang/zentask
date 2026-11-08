import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class TasksModelTests: XCTestCase {
    private func model() async -> TasksModel {
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        await m.reload()
        return m
    }

    private var ids: (TasksModel) -> [String] = { m in m.sections.flatMap(\.rows).map(\.task.id) }

    func testQuickAddCreatesParsedTaskInTheCurrentList() async {
        let m = await model()
        m.sidebar = .smart(.today)
        m.quickAdd("Pay rent #home !high")
        await m.reload()
        let task = try! XCTUnwrap(m.selectedTask)
        XCTAssertEqual(task.title, "Pay rent")
        XCTAssertEqual(task.tags, ["home"])
        XCTAssertEqual(task.priority, 5)
        XCTAssertEqual(ids(m), [task.id], "created in Today, due today")
    }

    func testCompletingMovesTaskOutOfToday() async {
        let m = await model()
        m.sidebar = .smart(.today)
        m.quickAdd("Stretch")
        await m.reload()
        let task = try! XCTUnwrap(m.selectedTask)
        m.toggleComplete(task)
        await m.reload()
        XCTAssertTrue(ids(m).isEmpty)
        m.sidebar = .smart(.completed)
        await m.settle()
        XCTAssertEqual(ids(m), [task.id])
    }

    func testSwitchingListClearsSelectionAndRecomputes() async {
        let m = await model()
        m.sidebar = .smart(.inbox)
        m.quickAdd("In the inbox")
        await m.reload()
        XCTAssertNotNil(m.selection)
        m.sidebar = .smart(.trash)
        await m.settle()
        XCTAssertNil(m.selection)
        XCTAssertTrue(m.sections.isEmpty)
        XCTAssertEqual(m.count(.smart(.inbox)), 1)
    }
}

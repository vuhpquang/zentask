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

    func testListsSectionsAndViewOptions() async {
        let m = await model()
        let list = m.core.listActions.createList(name: "Trip", color: "#2E9E88", groupId: nil)
        let column = m.core.listActions.createColumn(projectId: list.id, name: "Packing")
        await m.reload()
        m.sidebar = .project(list.id)
        m.quickAdd("Passport")
        await m.reload()
        await m.settle()
        XCTAssertEqual(m.sections.map(\.title), ["Packing", "No section"], "an empty section stays visible")
        XCTAssertEqual(m.sections.first?.columnId, column.id)
        m.setGroup("none")
        await m.settle()
        XCTAssertEqual(m.sections.map(\.title), [""])
        XCTAssertEqual(m.core.viewOptions(filter: SidebarItem.project(list.id).filter).group, "none", "saved as a synced preference")
    }

    func testSmartListAndArchive() async {
        let m = await model()
        m.sidebar = .smart(.inbox)
        m.quickAdd("Urgent thing !high")
        m.quickAdd("Calm thing")
        let f = m.core.saveFilter(id: nil, name: "Hot", rule: FilterRule(lists: [], tags: [], priorities: [KotlinInt(int: 5)], due: nil, keyword: nil))
        await m.reload()
        m.sidebar = .custom(f.id)
        await m.settle()
        XCTAssertEqual(m.sections.flatMap(\.rows).map(\.task.title), ["Urgent thing"])
        XCTAssertEqual(m.title, "Hot")
        let list = m.core.listActions.createList(name: "Old", color: nil, groupId: nil)
        try? m.core.listActions.archive(id: list.id)
        await m.reload()
        XCTAssertEqual(m.archivedProjects.map(\.name), ["Old"])
        XCTAssertFalse(m.listProjects.contains { $0.id == list.id })
    }
}

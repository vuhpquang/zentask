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

    func testQuickAddCreatesParsedTaskInTheCurrentList() async throws {
        let m = await model()
        m.sidebar = .smart(.today)
        m.quickAdd("Pay rent #home !high")
        await m.reload()
        let task = try XCTUnwrap(m.selectedTask)
        XCTAssertEqual(task.title, "Pay rent")
        XCTAssertEqual(task.tags, ["home"])
        XCTAssertEqual(task.priority, 5)
        XCTAssertEqual(ids(m), [task.id], "created in Today, due today")
    }

    func testCompletingMovesTaskOutOfToday() async throws {
        let m = await model()
        m.sidebar = .smart(.today)
        m.quickAdd("Stretch")
        await m.reload()
        let task = try XCTUnwrap(m.selectedTask)
        m.toggleComplete(task)
        await m.reload()
        XCTAssertTrue(ids(m).isEmpty)
        m.sidebar = .smart(.completed)
        await m.settle()
        XCTAssertEqual(ids(m), [task.id])
    }

    func testSwitchingListClearsSelectionAndRecomputes() async throws {
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

    func testListsSectionsAndViewOptions() async throws {
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

    func testSmartListAndArchive() async throws {
        let m = await model()
        m.sidebar = .smart(.inbox)
        m.quickAdd("Urgent thing !high")
        m.quickAdd("Calm thing")
        let f = m.core.saveFilter(id: nil, name: "Hot", rule: FilterRule(lists: [], tags: [], priorities: [KotlinInt(int: 5)], due: nil, keyword: nil, match: "all", excludeLists: [], excludeTags: [], tagsAll: false), icon: nil)
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

    func testDropOntoListsDatesAndTagsAndIgnoresStaleIds() async throws {
        let m = await model()
        let list = m.core.listActions.createList(name: "Trip", color: nil, groupId: nil)
        m.sidebar = .smart(.inbox)
        m.quickAdd("Passport")
        await m.reload()
        let id = try XCTUnwrap(m.selection)
        XCTAssertTrue(m.drop([id], onto: .project(list.id)))
        XCTAssertTrue(m.drop([id], onto: .smart(.tomorrow)))
        XCTAssertTrue(m.drop([id], onto: .tag("travel")))
        await m.reload()
        let t = try XCTUnwrap(m.index?.task(id: id))
        XCTAssertEqual(t.projectId, list.id)
        XCTAssertEqual(t.tags, ["travel"])
        XCTAssertTrue(Calendar.current.isDateInTomorrow(Date(timeIntervalSince1970: Double(t.dueDate!.int64Value) / 1000)))
        XCTAssertFalse(m.drop(["not-a-task"], onto: .project(list.id)), "stale or foreign payloads are ignored")
        m.core.actions.trash(id: id)
        await m.reload()
        XCTAssertFalse(m.drop([id], onto: .smart(.inbox)), "trashed tasks don't move")
    }

    func testReorderAndDropIntoSection() async throws {
        let m = await model()
        let list = m.core.listActions.createList(name: "Board", color: nil, groupId: nil)
        let doing = m.core.listActions.createColumn(projectId: list.id, name: "Doing")
        m.sidebar = .project(list.id)
        for title in ["A", "B", "C"] { m.quickAdd(title) }
        await m.reload(); await m.settle()
        let noSection = try XCTUnwrap(m.sections.first { $0.columnId == nil && !$0.isCompleted })
        XCTAssertEqual(noSection.rows.map(\.task.title), ["C", "B", "A"], "new tasks go to the top")
        m.move(in: noSection, from: IndexSet(integer: 2), to: 0)
        await m.reload(); await m.settle()
        XCTAssertEqual(m.sections.first { $0.columnId == nil }?.rows.map(\.task.title), ["A", "C", "B"])
        let b = m.sections.first { $0.columnId == nil }!.rows.last!.task.id
        XCTAssertTrue(m.drop([b], intoColumn: doing.id))
        await m.reload(); await m.settle()
        XCTAssertEqual(m.sections.first { $0.columnId == doing.id }?.rows.map(\.task.title), ["B"])
    }

    func testSmartListModesHideAndShowIfNotEmpty() async throws {
        let m = await model()
        m.core.setSmartListMode(list: .today, mode: "hide")
        m.core.setSmartListMode(list: .trash, mode: "nonempty")
        await m.reload()
        XCTAssertFalse(m.shows(.today))
        XCTAssertFalse(m.shows(.trash), "empty trash is hidden")
        m.sidebar = .smart(.inbox)
        m.quickAdd("Throw away")
        await m.reload()
        m.core.actions.trash(id: try XCTUnwrap(m.selection))
        await m.reload()
        XCTAssertTrue(m.shows(.trash))
        XCTAssertTrue(m.shows(.inbox))
    }

    func testSidebarItemKeysRoundTrip() {
        for item in [SidebarItem.smart(.next7Days), .project("p1"), .tag("work"), .custom("f:1")] {
            XCTAssertEqual(SidebarItem(storageKey: item.storageKey), item)
        }
        XCTAssertNil(SidebarItem(storageKey: "nonsense"))
    }

    func testFoldingAGroupIsRememberedPerList() async throws {
        let saved = UserDefaults.standard.stringArray(forKey: "tasks.collapsed")
        defer { UserDefaults.standard.set(saved, forKey: "tasks.collapsed") }
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        _ = m.core.actions.create(title: "A", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        m.sidebar = .smart(.all)
        await m.reload()
        m.setOptions(m.options.with(sort: "priority", group: "list", order: "desc", hideDetails: true))
        await m.settle()
        XCTAssertEqual(m.core.viewOptions(filter: SidebarItem.smart(.all).filter).order, "desc", "options are saved")
        XCTAssertTrue(m.options.hideDetails)
        let section = try XCTUnwrap(m.sections.first)
        XCTAssertEqual(section.key, "list:inbox")
        XCTAssertFalse(m.isCollapsed(section, in: "smart:ALL"))
        m.toggleCollapsed(section, in: "smart:ALL")
        XCTAssertTrue(m.isCollapsed(section, in: "smart:ALL"))
        XCTAssertFalse(m.isCollapsed(section, in: "smart:TODAY"), "per list")
        XCTAssertTrue(TasksModel(core: m.core).isCollapsed(section, in: "smart:ALL"), "remembered")
        m.toggleCollapsed(section, in: "smart:ALL")
    }

    func testTaskPanelShowsOnlyUndatedWhenAsked() async throws {
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        _ = m.core.actions.create(title: "Undated", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        _ = m.core.actions.create(title: "Dated", filter: nil, projectId: nil, priority: nil, tags: [], due: KotlinLong(value: Int64(Date.now.timeIntervalSince1970 * 1000)), allDay: true)
        await m.reload()
        let keys = ["calendar.panel.item", "calendar.panel.options", "calendar.panel.undated"]
        let saved = keys.map { UserDefaults.standard.object(forKey: $0) }
        defer { for (k, v) in zip(keys, saved) { UserDefaults.standard.set(v, forKey: k) } }
        let panel = TaskPanelState()
        panel.item = .smart(.all)
        panel.onlyUndated = false
        await panel.recompute(m)
        XCTAssertEqual(Set(panel.sections.flatMap(\.rows).map(\.task.title)), ["Undated", "Dated"])
        panel.onlyUndated = true
        await panel.recompute(m)
        XCTAssertEqual(panel.sections.flatMap(\.rows).map(\.task.title), ["Undated"])
    }

    func testRevealShowsATaskWhereItIsListedAndUnfoldsItsGroup() async throws {
        let saved = UserDefaults.standard.stringArray(forKey: "tasks.collapsed")
        defer { UserDefaults.standard.set(saved, forKey: "tasks.collapsed") }
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        let done = m.core.actions.create(title: "Done", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        m.core.actions.complete(id: done.id)
        let open = m.core.actions.create(title: "Open", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        await m.reload()
        m.reveal(done.id)
        XCTAssertEqual(m.sidebar, .smart(.completed))
        XCTAssertEqual(m.selection, done.id)
        m.sidebar = .project("inbox")
        await m.settle()
        m.setOptions(m.options.with(group: "priority"))
        await m.settle()
        let group = try XCTUnwrap(m.sections.first { s in s.rows.contains { $0.task.id == open.id } })
        m.toggleCollapsed(group, in: "project:inbox")
        m.reveal(open.id)
        XCTAssertEqual(m.sidebar, .project("inbox"))
        for _ in 0..<20 where m.isCollapsed(group, in: "project:inbox") { try await _Concurrency.Task.sleep(for: .milliseconds(20)) }
        XCTAssertFalse(m.isCollapsed(group, in: "project:inbox"), "unfolded to show the task")
        m.setOptions(m.options.with(group: ""))
    }

    func testViewModesKanbanDropsAndSummary() async throws {
        let m = TasksModel(core: ZenTaskCore.companion.open(dbPath: nil))
        let list = m.core.listActions.createList(name: "Board", color: nil, groupId: nil)
        let t = m.core.actions.create(title: "Card", filter: nil, projectId: list.id, priority: nil, tags: [], due: nil, allDay: true)
        await m.reload()
        m.sidebar = .project(list.id)
        await m.settle()
        XCTAssertEqual(m.viewMode, "list")
        m.setViewMode("kanban")
        await m.reload()
        XCTAssertEqual(m.viewMode, "kanban", "a project keeps its own view mode")
        m.sidebar = .smart(.all)
        await m.settle()
        m.setViewMode("timeline")
        XCTAssertEqual(m.viewMode, "timeline", "smart lists keep it in their view options")
        m.setOptions(m.options.with(group: "priority"))
        await m.settle()
        let high = TaskSection(title: "High", rows: [], isCompleted: false, columnId: nil, key: "prio:5", color: nil)
        XCTAssertTrue(m.drop([t.id], into: high))
        XCTAssertEqual(m.core.task(id: t.id)?.priority, 5)
        m.core.actions.complete(id: t.id)
        await m.reload()
        let day = Int64(Calendar.current.startOfDay(for: .now).timeIntervalSince1970 * 1000)
        let summary = m.core.summary(index: try XCTUnwrap(m.index), fromDay: day, toDay: day)
        XCTAssertEqual(summary.done.flatMap(\.rows).map(\.task.title), ["Card"])
        XCTAssertTrue(summary.text().contains("✓ Card"))
        m.setOptions(m.options.with(group: "", view: ""))
    }
}

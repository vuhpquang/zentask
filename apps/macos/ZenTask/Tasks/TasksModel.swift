import Foundation
import Observation
@preconcurrency import ZenTaskShared

typealias ZTask = ZenTaskShared.Task

/// A sidebar entry, Swift-side (hashable for List selection), mapped to the shared TaskFilter.
enum SidebarItem: Hashable {
    case smart(SmartList)
    case project(String)
    case tag(String)

    var filter: TaskFilter {
        switch self {
        case .smart(let list): TaskFilterSmart(list: list)
        case .project(let id): TaskFilterInProject(id: id)
        case .tag(let name): TaskFilterWithTag(name: name)
        }
    }
}

/// State of the Tasks screen: the in-memory index (reloaded off the main thread when any record
/// changes), the selected list and task, and the actions the views call.
@MainActor
@Observable
final class TasksModel {
    let core: ZenTaskCore

    var sidebar: SidebarItem = .smart(.today) { didSet { recompute(); if oldValue != sidebar { selection = nil } } }
    var selection: String?
    var showCompleted = false

    private(set) var index: TaskIndex?
    private(set) var sections: [TaskSection] = []
    private(set) var counts: [String: KotlinInt] = [:]
    private(set) var projects: [Project] = []
    private(set) var groups: [ProjectGroup] = []
    private(set) var tags: [Tag] = []
    private var started = false
    private var pendingReload: _Concurrency.Task<Void, Never>?

    init(core: ZenTaskCore) {
        self.core = core
    }

    func start() {
        guard !started else { return }
        started = true
        _Concurrency.Task { await reload() }
        _Concurrency.Task {
            for await _ in core.recordChanges { scheduleReload() }
        }
    }

    /** Coalesces bursts of writes (an import, a sync page) into one reload. */
    private func scheduleReload() {
        pendingReload?.cancel()
        pendingReload = _Concurrency.Task {
            try? await _Concurrency.Task.sleep(for: .milliseconds(80))
            guard !_Concurrency.Task.isCancelled else { return }
            await reload()
        }
    }

    func reload() async {
        guard let idx = try? await core.loadIndex() else { return }
        index = idx
        projects = idx.projects.filter { $0.trashedAt == nil && !$0.deleted }
        tags = idx.tags.filter { !$0.deleted }
        groups = core.projectGroups()
        recompute()
    }

    func recompute() {
        guard let index else { return }
        sections = core.sections(index: index, filter: sidebar.filter)
        counts = core.sidebarCounts(index: index)
    }

    // MARK: Lookups

    var title: String {
        switch sidebar {
        case .smart(let list): Self.title(list)
        case .project(let id): projects.first { $0.id == id }?.name ?? "List"
        case .tag(let name): "#" + name
        }
    }

    func count(_ item: SidebarItem) -> Int {
        Int(counts[core.filterKey(filter: item.filter)]?.int32Value ?? 0)
    }

    var selectedTask: ZTask? {
        guard let selection else { return nil }
        return index?.task(id: selection)
    }

    func items(of task: ZTask) -> [ChecklistItem] { index?.items(taskId: task.id) ?? [] }
    func subtasks(of task: ZTask) -> [ZTask] { index?.subtasks(taskId: task.id) ?? [] }
    func projectName(_ id: String) -> String { projects.first { $0.id == id }?.name ?? "Inbox" }
    func tagColor(_ name: String) -> String? { tags.first { $0.name == name }?.color }

    func day(of task: ZTask) -> Date? {
        guard let index, let ms = core.taskDayMillis(index: index, task: task) else { return nil }
        return Date(timeIntervalSince1970: Double(ms.int64Value) / 1000)
    }

    var listProjects: [Project] {
        projects.filter { !$0.isInbox && !$0.closed }.sorted { $0.sortOrder < $1.sortOrder }
    }

    // MARK: Actions

    func quickAdd(_ text: String) {
        guard let task = core.quickAdd(text: text, filter: sidebar.filter, projects: projects) else { return }
        selection = task.id
    }

    func parse(_ text: String) -> QuickAdd { core.parseQuickAdd(text: text, projects: projects) }

    func toggleComplete(_ task: ZTask) {
        if task.isDone || task.status == -1 { core.actions.uncomplete(id: task.id) } else { core.actions.complete(id: task.id) }
    }

    func trashSelected() {
        guard let task = selectedTask else { return }
        if task.trashedAt != nil { return }
        core.actions.trash(id: task.id)
        selection = nil
    }

    static func title(_ list: SmartList) -> String {
        switch list {
        case .all: "All"
        case .today: "Today"
        case .tomorrow: "Tomorrow"
        case .next7Days: "Next 7 days"
        case .inbox: "Inbox"
        case .completed: "Completed"
        case .wontDo: "Won't do"
        case .trash: "Trash"
        }
    }

    static func symbol(_ list: SmartList) -> String {
        switch list {
        case .all: "square.stack"
        case .today: "sun.max"
        case .tomorrow: "sunrise"
        case .next7Days: "calendar"
        case .inbox: "tray"
        case .completed: "checkmark.circle"
        case .wontDo: "xmark.circle"
        case .trash: "trash"
        }
    }
}

extension ZTask {
    var isDone: Bool { status == 2 || status == 1 }
}

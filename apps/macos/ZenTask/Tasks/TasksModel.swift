import Foundation
import SwiftUI
import Observation
@preconcurrency import ZenTaskShared

typealias ZTask = ZenTaskShared.Task

/// A sidebar entry, Swift-side (hashable for List selection), mapped to the shared TaskFilter.
enum SidebarItem: Hashable {
    case smart(SmartList)
    case project(String)
    case tag(String)
    case custom(String)

    /// A stable string for remembering a choice ("smart:TODAY", "project:<id>", "tag:x", "custom:<id>").
    var storageKey: String {
        switch self {
        case .smart(let list): "smart:\(list.name)"
        case .project(let id): "project:\(id)"
        case .tag(let name): "tag:\(name)"
        case .custom(let id): "custom:\(id)"
        }
    }

    init?(storageKey: String) {
        let parts = storageKey.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return nil }
        switch parts[0] {
        case "smart": guard let list = TasksModel.allSmart.first(where: { $0.name == parts[1] }) else { return nil }; self = .smart(list)
        case "project": self = .project(parts[1])
        case "tag": self = .tag(parts[1])
        case "custom": self = .custom(parts[1])
        default: return nil
        }
    }

    var filter: TaskFilter {
        switch self {
        case .smart(let list): TaskFilterSmart(list: list)
        case .project(let id): TaskFilterInProject(id: id)
        case .tag(let name): TaskFilterWithTag(name: name)
        case .custom(let id): TaskFilterCustom(id: id)
        }
    }
}

/// Sheets the Tasks screen can show (create/edit lists, folders, sections, smart lists).
enum ListSheet: Identifiable {
    case newList, editList(String), newFolder, renameFolder(String), newSection(String), renameSection(String, String), newFilter, editFilter(String)

    var id: String {
        switch self {
        case .newList: "newList"
        case .editList(let id): "editList." + id
        case .newFolder: "newFolder"
        case .renameFolder(let id): "renameFolder." + id
        case .newSection(let id): "newSection." + id
        case .renameSection(let id, _): "renameSection." + id
        case .newFilter: "newFilter"
        case .editFilter(let id): "editFilter." + id
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
    /// The task a "Delete permanently" confirmation is open for.
    var confirmDelete: ZTask?
    /// The list a "Delete permanently" confirmation is open for.
    var confirmDeleteList: Project?
    /// The smart list a delete confirmation is open for.
    var confirmDeleteFilter: Filter?
    var sheet: ListSheet?
    /// A context-menu "Pick a date…" asks the detail pane to open its date popover for this task.
    var requestDateEdit: String?

    /// Opens a `zentask://task/<id>` link: shows the task in All (or Trash).
    func open(_ url: URL) {
        guard url.scheme == "zentask", url.host() == "task", let id = url.pathComponents.dropFirst().first, let task = index?.task(id: id) else { return }
        sidebar = task.trashedAt != nil ? .smart(.trash) : .project(task.projectId)
        selection = id
    }
    private(set) var options = ViewOptions(sort: "", group: "", showCompleted: false, order: "", hideCompleted: false, hideDetails: false)

    /// Collapsed groups ("<list key>|<section key>"), remembered on this Mac.
    private(set) var collapsed: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "tasks.collapsed") ?? [])
    /// The sidebar column (☰ in the list header toggles it).
    var columnVisibility: NavigationSplitViewVisibility = .all
    /// The calendar panel beside the task detail.
    var showCalendarPanel: Bool = UserDefaults.standard.bool(forKey: "tasks.calendarPanel") {
        didSet { UserDefaults.standard.set(showCalendarPanel, forKey: "tasks.calendarPanel") }
    }

    func isCollapsed(_ section: TaskSection, in scope: String) -> Bool { collapsed.contains("\(scope)|\(section.key)") }

    func toggleCollapsed(_ section: TaskSection, in scope: String) {
        let key = "\(scope)|\(section.key)"
        if collapsed.contains(key) { collapsed.remove(key) } else { collapsed.insert(key) }
        UserDefaults.standard.set(Array(collapsed).sorted(), forKey: "tasks.collapsed")
    }

    private(set) var index: TaskIndex?
    private(set) var sections: [TaskSection] = []
    private(set) var counts: [String: KotlinInt] = [:]
    private(set) var smartModes: [SmartList: String] = [:]
    private(set) var projects: [Project] = []
    private(set) var groups: [ProjectGroup] = []
    private(set) var tags: [Tag] = []
    private var started = false
    /// Set by the app (not in tests or snapshots): local notifications for task reminders.
    var reminders: Reminders?
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
        counts = core.sidebarCounts(index: idx)
        smartModes = Dictionary(uniqueKeysWithValues: TasksModel.allSmart.map { ($0, core.smartListMode(list: $0)) })
        recompute()
        await recomputeTask?.value
        if let reminders { await reminders.reschedule(core.plannedReminders(index: idx)) }
    }

    private var recomputeTask: _Concurrency.Task<Void, Never>?

    /// Waits for the pending recompute (tests, snapshots).
    func settle() async { await recomputeTask?.value }

    var filters: [Filter] { index?.filters ?? [] }
    var archivedProjects: [Project] { projects.filter { $0.closed && !$0.isInbox }.sorted { $0.name < $1.name } }
    var trashedProjects: [Project] { (index?.projects ?? []).filter { $0.trashedAt != nil && !$0.deleted } }
    var currentProject: Project? { if case .project(let id) = sidebar { return projects.first { $0.id == id } }; return nil }

    func setSort(_ sort: String) { saveOptions(options.with(sort: sort)) }
    func setGroup(_ group: String) { saveOptions(options.with(group: group)) }
    func setOptions(_ o: ViewOptions) { saveOptions(o) }

    private func saveOptions(_ o: ViewOptions) {
        core.setViewOptions(filter: sidebar.filter, options: o)
        options = o
        recompute()
    }

    /// Sections for the selected list, computed off the main thread (the index is immutable).
    func recompute() {
        guard let index else { return }
        let core = self.core
        let filter = sidebar.filter
        let item = sidebar
        options = core.viewOptions(filter: filter)
        let opts = options
        recomputeTask?.cancel()
        recomputeTask = _Concurrency.Task {
            let result = await _Concurrency.Task.detached(priority: .userInitiated) { core.sections(index: index, filter: filter, options: opts) }.value
            guard !_Concurrency.Task.isCancelled, item == self.sidebar, index === self.index else { return }
            self.sections = result
        }
    }

    // MARK: Lookups

    var title: String { title(of: sidebar) }

    func title(of item: SidebarItem) -> String {
        switch item {
        case .smart(let list): Self.title(list)
        case .project(let id): projects.first { $0.id == id }?.name ?? "List"
        case .tag(let name): "#" + name
        case .custom(let id): filters.first { $0.id == id }?.name ?? "Smart list"
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

    /// Lists quick add may file into: the Inbox and open (not archived) lists.
    private var quickAddProjects: [Project] { projects.filter { !$0.closed } }

    func quickAdd(_ text: String, ignored: Set<Int> = []) {
        let skip = Set(ignored.map { KotlinInt(int: Int32($0)) })
        guard let task = core.quickAdd(text: text, filter: sidebar.filter, projects: quickAddProjects, ignored: skip) else { return }
        selection = task.id
    }

    func parse(_ text: String, ignored: Set<Int> = []) -> QuickAdd {
        core.parseQuickAdd(text: text, projects: quickAddProjects, ignored: Set(ignored.map { KotlinInt(int: Int32($0)) }))
    }

    func toggleComplete(_ task: ZTask) {
        if task.isDone || task.status == -1 { core.actions.uncomplete(id: task.id) } else { core.actions.complete(id: task.id) }
    }

    // MARK: Drag and drop

    /// Drops dragged task ids onto a sidebar item: a list moves them, Today/Tomorrow dates them,
    /// a tag tags them. Ids that no longer exist (or are in Trash) are ignored. Returns whether anything changed.
    @discardableResult
    func drop(_ ids: [String], onto item: SidebarItem) -> Bool {
        let live = ids.compactMap { index?.task(id: $0) }.filter { $0.trashedAt == nil && !$0.deleted }
        guard !live.isEmpty else { return false }
        let actions = core.actions
        for task in live {
            switch item {
            case .project(let id):
                if task.projectId == id { continue } // dropped on its own list: keep its section
                actions.move(id: task.id, projectId: id)
            case .smart(.inbox): actions.move(id: task.id, projectId: "inbox")
            case .smart(.today), .smart(.tomorrow):
                let day = Calendar.current.startOfDay(for: item == .smart(.today) ? .now : Calendar.current.date(byAdding: .day, value: 1, to: .now)!)
                actions.setDue(id: task.id, due: KotlinLong(value: Int64(day.timeIntervalSince1970 * 1000)), allDay: true)
            case .tag(let name): actions.setTags(id: task.id, tags: task.tags + [name])
            default: return false
            }
        }
        return true
    }

    /// Drops tasks onto a section header: they move into that section of the current list.
    @discardableResult
    func drop(_ ids: [String], intoColumn columnId: String?) -> Bool {
        let live = ids.compactMap { index?.task(id: $0) }.filter { $0.trashedAt == nil }
        guard case .project(let projectId) = sidebar, !live.isEmpty else { return false }
        let rows = sections.first { $0.columnId == columnId }?.rows ?? []
        var after = rows.last?.task.id
        for task in live {
            if task.projectId != projectId { core.actions.move(id: task.id, projectId: projectId) }
            core.listActions.reorderTask(id: task.id, after: after, before: nil, columnId: columnId, setColumn: true)
            after = task.id
        }
        return true
    }

    /// Reorders rows inside one section (List.onMove): places the moved task between its new neighbours.
    func move(in section: TaskSection, from source: IndexSet, to destination: Int) {
        var ids = section.rows.map(\.task.id)
        let moving = source.map { ids[$0] }
        ids.move(fromOffsets: source, toOffset: destination)
        for id in moving {
            guard let i = ids.firstIndex(of: id) else { continue }
            // Only a list's own sections set the column; any other grouping keeps the task's section.
            let columnSection = section.columnId != nil || (options.group.isEmpty || options.group == "column")
            core.listActions.reorderTask(
                id: id, after: i > 0 ? ids[i - 1] : nil, before: i + 1 < ids.count ? ids[i + 1] : nil,
                columnId: section.columnId, setColumn: columnSection
            )
        }
        if options.sort != "manual" && !isProjectDefaultManual { setSort("manual") }
    }

    /// Lists sort by hand-made order by default; a manual move elsewhere switches that list to manual.
    private var isProjectDefaultManual: Bool {
        if case .project = sidebar { return options.sort.isEmpty }
        return false
    }

    func trashSelected() {
        guard let task = selectedTask else { return }
        if task.trashedAt != nil { return }
        core.actions.trash(id: task.id)
        selection = nil
    }

    nonisolated static let allSmart: [SmartList] = [.today, .tomorrow, .next7Days, .inbox, .all, .completed, .wontDo, .trash]

    /// Sidebar visibility from Settings → Smart lists (Show / Show if not empty / Hide).
    func shows(_ list: SmartList) -> Bool {
        switch smartModes[list] ?? "show" {
        case "hide": return false
        case "nonempty": return count(.smart(list)) > 0 || sidebar == .smart(list)
        default: return true
        }
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

extension ViewOptions {
    /// A copy with some fields changed (Kotlin data classes have no default arguments in Swift).
    func with(sort: String? = nil, group: String? = nil, order: String? = nil, hideCompleted: Bool? = nil, hideDetails: Bool? = nil) -> ViewOptions {
        ViewOptions(sort: sort ?? self.sort, group: group ?? self.group, showCompleted: showCompleted, order: order ?? self.order,
                    hideCompleted: hideCompleted ?? self.hideCompleted, hideDetails: hideDetails ?? self.hideDetails)
    }
}

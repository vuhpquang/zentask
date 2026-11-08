import AppKit
import SwiftUI
@preconcurrency import ZenTaskShared

struct TaskListView: View {
    @Environment(TasksModel.self) private var tasks
    @FocusState private var quickAddFocused: Bool

    var body: some View {
        @Bindable var tasks = tasks
        VStack(spacing: 0) {
            ListHeader(
                item: Binding(get: { tasks.sidebar }, set: { tasks.sidebar = $0 }),
                options: tasks.options,
                setOptions: { tasks.setOptions($0) },
                toggleSidebar: {
                    withAnimation(Theme.List.foldAnimation) { tasks.columnVisibility = tasks.columnVisibility == .detailOnly ? .all : .detailOnly }
                }
            ) {
                if let project = tasks.currentProject {
                    Divider()
                    Button("Add section…", systemImage: "rectangle.split.1x2") { tasks.sheet = .newSection(project.id) }
                }
                Divider()
                Toggle("Calendar beside tasks", systemImage: "calendar", isOn: Binding(get: { tasks.showCalendarPanel }, set: { v in
                    withAnimation(Theme.List.foldAnimation) { tasks.showCalendarPanel = v }
                }))
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.top, Theme.Space.m)
            if !isReadOnlyList {
                QuickAddField(focused: $quickAddFocused) { text, ignored in tasks.quickAdd(text, ignored: ignored) }
                .padding(.horizontal, Theme.Space.l)
                .padding(.top, Theme.Space.m)
                .padding(.bottom, Theme.Space.s)
            }
            List(selection: $tasks.selection) {
                if case .smart(.trash) = tasks.sidebar, !tasks.trashedProjects.isEmpty {
                    Section("Lists") {
                        ForEach(tasks.trashedProjects, id: \.id) { p in
                            HStack {
                                Label(p.name, systemImage: "list.bullet")
                                Spacer()
                                Button("Restore") { tasks.core.listActions.restore(id: p.id) }
                                Button("Delete…", role: .destructive) { tasks.confirmDeleteList = p }
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
                ForEach(tasks.sections, id: \.key) { section in
                    Section {
                        if section.isCompleted {
                            GroupHeaderRow(title: section.title, count: section.rows.count, collapsed: !tasks.showCompleted) { tasks.showCompleted.toggle() }
                            if tasks.showCompleted { rows(section) }
                        } else if section.title.isEmpty {
                            rows(section)
                        } else {
                            let folded = tasks.isCollapsed(section, in: scope)
                            GroupHeaderRow(title: section.title, count: section.rows.count, collapsed: folded) { tasks.toggleCollapsed(section, in: scope) }
                                .dropDestination(for: String.self) { ids, _ in tasks.drop(ids, intoColumn: section.columnId) }
                                .contextMenu {
                                    if let columnId = section.columnId {
                                        Button("Rename section…") { tasks.sheet = .renameSection(columnId, section.title) }
                                        Button("Delete section") { tasks.core.listActions.deleteColumn(id: columnId) }
                                    }
                                }
                            if !folded { rows(section) }
                        }
                    }
                }
            }
            .listStyle(.inset)
            .environment(\.defaultMinListRowHeight, Theme.Size.minRow)
            .overlay {
                if tasks.sections.isEmpty { emptyState }
            }
            .onKeyPress(.space) {
                guard let task = tasks.selectedTask else { return .ignored }
                tasks.toggleComplete(task)
                return .handled
            }
            .onDeleteCommand { tasks.trashSelected() }
        }
        .navigationTitle(tasks.title)
        .toolbar(removing: .title)
        .confirmationDialog(
            "Delete \"\(tasks.confirmDelete?.title ?? "")\" permanently?",
            isPresented: Binding(get: { tasks.confirmDelete != nil }, set: { if !$0 { tasks.confirmDelete = nil } })
        ) {
            Button("Delete permanently", role: .destructive) {
                if let t = tasks.confirmDelete { try? tasks.core.actions.deleteForever(id: t.id) }
                tasks.confirmDelete = nil
            }
            Button("Cancel", role: .cancel) { tasks.confirmDelete = nil }
        } message: {
            Text("The task, its subtasks in Trash and its checklist are deleted on every device. This can't be undone.")
        }
        .focusedSceneValue(\.focusQuickAdd) { quickAddFocused = true }
    }

    /// Collapsed groups are remembered per list.
    private var scope: String { tasks.sidebar.storageKey }

    private var isReadOnlyList: Bool {
        if case .smart(let list) = tasks.sidebar { return list == .completed || list == .wontDo || list == .trash }
        return false
    }

    @ViewBuilder
    private func rows(_ section: TaskSection) -> some View {
        ForEach(section.rows, id: \.task.id) { row in
            TaskRowView(task: row.task, row: row, showProject: showsProject, details: !tasks.options.hideDetails)
                .tag(row.task.id)
                .draggable(row.task.id)
                .listRowBackground(Color(hex: section.color).map { GroupBar(color: $0) })
            // Subtasks are their own rows: selectable, reachable with the arrow keys, draggable alone.
            ForEach(row.subtasks, id: \.id) { sub in
                TaskRowView(task: sub, row: nil, showProject: false, details: !tasks.options.hideDetails)
                    .padding(.leading, Theme.Row.indent)
                    .listRowBackground(Color(hex: section.color).map { GroupBar(color: $0) })
                    .tag(sub.id)
                    .draggable(sub.id)
            }
        }
        .onMove(perform: canReorder(section) ? { from, to in tasks.move(in: section, from: from, to: to) } : nil)
    }

    /// Manual reordering makes sense inside a list's own sections (not date or priority groups).
    private func canReorder(_ section: TaskSection) -> Bool {
        guard case .project = tasks.sidebar, !section.isCompleted else { return false }
        // Moves map to rows only when no subtask rows sit between them.
        guard section.rows.allSatisfy({ $0.subtasks.isEmpty }) else { return false }
        return tasks.options.group.isEmpty || tasks.options.group == "column" || tasks.options.group == "none"
    }

    private var showsProject: Bool {
        if case .project = tasks.sidebar { return false }
        return true
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label(emptyTitle, systemImage: "checkmark.circle")
        } description: {
            Text(isReadOnlyList ? "" : "Type a task above and press Return")
        }
    }

    private var emptyTitle: String {
        if case .smart(let list) = tasks.sidebar {
            switch list {
            case .today: return "Nothing due today"
            case .trash: return "Trash is empty"
            case .completed: return "No completed tasks"
            default: break
            }
        }
        return "No tasks"
    }
}

/// One task line: priority-coloured checkbox, title, date, list, tags, checklist progress.
struct TaskRowView: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask
    let row: TaskRow?
    let showProject: Bool
    var details = true

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.m) {
            CheckButton(task: task) { tasks.toggleComplete(task) }
            VStack(alignment: .leading, spacing: Theme.Row.metaSpacing) {
                Text(task.title.isEmpty ? "Untitled" : task.title)
                    .font(.body)
                    .strikethrough(task.isDone)
                    .foregroundStyle(task.isDone || task.status == -1 ? .secondary : .primary)
                    .lineLimit(2)
                if details, let preview = TaskRowView.preview(task) {
                    Text(preview).font(.callout).foregroundStyle(.secondary).lineLimit(1)
                }
                if hasMeta { meta }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, Theme.Row.verticalPadding)
        .padding(.horizontal, Theme.Space.xs)
        .contentShape(Rectangle())
        .contextMenu { TaskMenu(task: task) }
    }

    /// The first non-empty line of the notes (or a checklist's description), without Markdown marks.
    static func preview(_ task: ZTask) -> String? {
        let text = task.kind == "CHECKLIST" && !task.desc.isEmpty ? task.desc : task.content
        let line = text.split(whereSeparator: \.isNewline).lazy
            .map { $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "#>*-• ")) }
            .first { !$0.isEmpty }
        return line.map { String($0.prefix(200)) }
    }

    private var hasMeta: Bool {
        tasks.day(of: task) != nil || !task.tags.isEmpty || (row?.checklistTotal ?? 0) > 0 || showProject || task.repeatRule != nil
    }

    private var meta: some View {
        HStack(spacing: Theme.Space.s) {
            if let day = tasks.day(of: task) {
                Label(DateText.short(day: day, task: task), systemImage: "calendar")
                    .labelStyle(.titleAndIcon)
                    .foregroundStyle(row?.overdue == true ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
            }
            if task.repeatRule != nil {
                Image(systemName: "repeat").foregroundStyle(.secondary)
            }
            if let row, row.checklistTotal > 0 {
                Label("\(row.checklistDone)/\(row.checklistTotal)", systemImage: "checklist").foregroundStyle(.secondary)
            }
            ForEach(task.tags, id: \.self) { tag in
                TagPill(name: tag, color: tasks.tagColor(tag))
            }
            if showProject, let row, !row.projectName.isEmpty {
                Text(row.projectName).foregroundStyle(.secondary)
            }
        }
        .font(.caption)
        .lineLimit(1)
    }
}

struct CheckButton: View {
    let task: ZTask
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: Theme.Row.checkbox))
                .foregroundStyle(task.isDone ? .secondary : Theme.PriorityColor.color(task.priority))
        }
        .buttonStyle(.plain)
        .help(task.isDone ? "Mark as not done" : "Complete")
    }

    private var symbol: String {
        if task.status == -1 { return "xmark.square" }
        return task.isDone ? "checkmark.square.fill" : "square"
    }
}

struct TagPill: View {
    let name: String
    let color: String?

    var body: some View {
        Text(name)
            .padding(.horizontal, Theme.Space.xs + Theme.Space.xxs)
            .padding(.vertical, Theme.Space.xxs / 2)
            .background((Color(hex: color) ?? .secondary).opacity(Theme.Opacity.pill), in: RoundedRectangle(cornerRadius: Theme.Row.pillRadius, style: .continuous))
            .foregroundStyle(.primary)
    }
}

/// Right-click menu for a task, laid out like TickTick's: a row of date shortcuts, a row of priority
/// flags, then Won't do, Move to, Tags, Duplicate, Copy link and Delete.
struct TaskMenu: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask

    var body: some View {
        items.labelStyle(.titleAndIcon) // menus show each item's icon, as TickTick's does
    }

    @ViewBuilder
    private var items: some View {
        let actions = tasks.core.actions
        if task.trashedAt != nil {
            Button { actions.restore(id: task.id) } label: { Label("Restore", systemImage: "arrow.uturn.backward") }
            Button(role: .destructive) { tasks.confirmDelete = task } label: { Label("Delete permanently…", systemImage: "trash.slash") }
        } else {
            Section("Date") {
                ControlGroup {
                    Button { setDay(0) } label: { Label("Today", systemImage: "sun.max") }
                    Button { setDay(1) } label: { Label("Tomorrow", systemImage: "sunrise") }
                    Button { setDay(7) } label: { Label("Next week", systemImage: "calendar.badge.plus") }
                    Button { tasks.selection = task.id; tasks.requestDateEdit = task.id } label: { Label("Pick a date…", systemImage: "calendar") }
                    Button { actions.setDue(id: task.id, due: nil, allDay: false) } label: { Label("No date", systemImage: "xmark.square") }
                }
                .controlGroupStyle(.palette)
            }
            Section("Priority") {
                ControlGroup {
                    ForEach(PriorityOption.all, id: \.value) { p in
                        Button { actions.setPriority(id: task.id, priority: p.value) } label: {
                            Label { Text(p.title) } icon: { Symbols.tinted(p.value == 0 ? "flag" : "flag.fill", Theme.PriorityColor.nsColor(p.value)) }
                        }
                    }
                }
                .controlGroupStyle(.palette)
            }
            Divider()
            if task.isDone || task.status == -1 {
                Button { actions.uncomplete(id: task.id) } label: { Label("Reopen", systemImage: "arrow.uturn.backward.circle") }
            } else {
                Button { tasks.toggleComplete(task) } label: { Label("Complete", systemImage: "checkmark.circle") }
                Button { actions.wontDo(id: task.id) } label: { Label("Won't do", systemImage: "xmark.circle") }
            }
            Menu {
                Button { actions.move(id: task.id, projectId: "inbox") } label: { Label("Inbox", systemImage: "tray") }
                ForEach(tasks.listProjects, id: \.id) { p in
                    Button { actions.move(id: task.id, projectId: p.id) } label: {
                        Label { Text(p.name) } icon: { Symbols.tinted("list.bullet", NSColor(Color(hex: p.color) ?? .secondary)) }
                    }
                }
            } label: { Label("Move to", systemImage: "arrow.right.square") }
            Menu {
                ForEach(tasks.tags, id: \.id) { tag in
                    Toggle(isOn: Binding(get: { task.tags.contains(tag.name) }, set: { on in
                        actions.setTags(id: task.id, tags: on ? task.tags + [tag.name] : task.tags.filter { $0 != tag.name })
                    })) { Text("#" + tag.name) }
                }
                if tasks.tags.isEmpty { Text("No tags yet") }
            } label: { Label("Tags", systemImage: "tag") }
            Divider()
            Button { tasks.selection = actions.duplicate(id: task.id).id } label: { Label("Duplicate", systemImage: "plus.square.on.square") }
            Button {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString("zentask://task/\(task.id)", forType: .string)
            } label: { Label("Copy link", systemImage: "link") }
            Divider()
            Button(role: .destructive) { actions.trash(id: task.id) } label: { Label("Delete", systemImage: "trash") }
        }
    }

    private func setDay(_ offset: Int) {
        let day = Calendar.current.date(byAdding: .day, value: offset, to: Calendar.current.startOfDay(for: .now))!
        tasks.core.actions.setDue(id: task.id, due: KotlinLong(value: Int64(day.timeIntervalSince1970 * 1000)), allDay: true)
    }
}

/// SF Symbols that keep their colour inside menus (NSMenu draws SwiftUI images as templates).
enum Symbols {
    static func tinted(_ name: String, _ color: NSColor) -> Image {
        let config = NSImage.SymbolConfiguration(paletteColors: [color])
        let image = NSImage(systemSymbolName: name, accessibilityDescription: nil)?.withSymbolConfiguration(config) ?? NSImage()
        image.isTemplate = false
        return Image(nsImage: image)
    }
}

struct PriorityOption {
    let title: String
    let value: Int32

    static let all = [
        PriorityOption(title: "High", value: 5), PriorityOption(title: "Medium", value: 3),
        PriorityOption(title: "Low", value: 1), PriorityOption(title: "None", value: 0),
    ]

    static func title(_ value: Int32) -> String { all.first { $0.value == value }?.title ?? "None" }
}

enum DateText {
    /// "Today", "Tomorrow", "Mon", "12 Nov" — plus the time for timed tasks.
    static func short(day: Date, task: ZTask) -> String {
        let cal = Calendar.current
        var text: String
        if cal.isDateInToday(day) {
            text = "Today"
        } else if cal.isDateInTomorrow(day) {
            text = "Tomorrow"
        } else if cal.isDateInYesterday(day) {
            text = "Yesterday"
        } else if let diff = cal.dateComponents([.day], from: cal.startOfDay(for: .now), to: day).day, (0..<7).contains(diff) {
            text = day.formatted(.dateTime.weekday(.abbreviated))
        } else {
            text = day.formatted(.dateTime.day().month(.abbreviated))
        }
        if !task.isAllDay, let ms = task.startDate ?? task.dueDate {
            text += " " + Date(timeIntervalSince1970: Double(ms.int64Value) / 1000).formatted(date: .omitted, time: .shortened)
        }
        return text
    }
}

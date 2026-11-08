import SwiftUI
@preconcurrency import ZenTaskShared

struct TaskListView: View {
    @Environment(TasksModel.self) private var tasks
    @FocusState private var quickAddFocused: Bool

    var body: some View {
        @Bindable var tasks = tasks
        VStack(spacing: 0) {
            if !isReadOnlyList {
                QuickAddField(focused: $quickAddFocused) { text, ignored in tasks.quickAdd(text, ignored: ignored) }
                .padding(.horizontal, Theme.Space.l)
                .padding(.vertical, Theme.Space.s)
            }
            List(selection: $tasks.selection) {
                ForEach(Array(tasks.sections.enumerated()), id: \.offset) { _, section in
                    if section.isCompleted {
                        Section(isExpanded: $tasks.showCompleted) {
                            rows(section)
                        } header: {
                            Text("\(section.title) · \(section.rows.count)")
                        }
                    } else {
                        Section {
                            rows(section)
                        } header: {
                            if !section.title.isEmpty { Text(section.title) }
                        }
                    }
                }
            }
            .listStyle(.inset)
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

    private var isReadOnlyList: Bool {
        if case .smart(let list) = tasks.sidebar { return list == .completed || list == .wontDo || list == .trash }
        return false
    }

    @ViewBuilder
    private func rows(_ section: TaskSection) -> some View {
        ForEach(section.rows, id: \.task.id) { row in
            TaskRowView(task: row.task, row: row, showProject: showsProject)
                .tag(row.task.id)
            ForEach(row.subtasks, id: \.id) { sub in
                TaskRowView(task: sub, row: nil, showProject: false)
                    .padding(.leading, Theme.Row.indent)
                    .tag(sub.id)
            }
        }
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

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            CheckButton(task: task) { tasks.toggleComplete(task) }
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                Text(task.title.isEmpty ? "Untitled" : task.title)
                    .strikethrough(task.isDone)
                    .foregroundStyle(task.isDone || task.status == -1 ? .secondary : .primary)
                    .lineLimit(2)
                if hasMeta { meta }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, Theme.Space.xxs)
        .contextMenu { TaskMenu(task: task) }
    }

    private var hasMeta: Bool {
        tasks.day(of: task) != nil || !task.tags.isEmpty || (row?.checklistTotal ?? 0) > 0 || showProject || task.repeatRule != nil
    }

    private var meta: some View {
        HStack(spacing: Theme.Space.s) {
            if let day = tasks.day(of: task) {
                Text(DateText.short(day: day, task: task))
                    .foregroundStyle(row?.overdue == true ? .red : .secondary)
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
                Text(row.projectName).foregroundStyle(.tertiary)
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

/// Right-click menu for a task.
struct TaskMenu: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask

    var body: some View {
        if task.trashedAt != nil {
            Button("Restore") { tasks.core.actions.restore(id: task.id) }
            Button("Delete permanently…", role: .destructive) { tasks.confirmDelete = task }
        } else {
            Button(task.isDone || task.status == -1 ? "Reopen" : "Complete") { tasks.toggleComplete(task) }
            Button("Won't do") { tasks.core.actions.wontDo(id: task.id) }
            Menu("Priority") {
                ForEach(PriorityOption.all, id: \.value) { p in
                    Button(p.title) { tasks.core.actions.setPriority(id: task.id, priority: p.value) }
                }
            }
            Divider()
            Button("Move to Trash", role: .destructive) { tasks.core.actions.trash(id: task.id) }
        }
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

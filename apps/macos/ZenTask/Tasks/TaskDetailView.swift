import SwiftUI
@preconcurrency import ZenTaskShared

/// The detail pane for one task. Text edits commit on Return or when the field loses focus; every
/// change goes through the shared TaskActions (offline first, synced).
struct TaskDetailView: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask

    @State private var draft = DetailDraft(title: "", notes: "")
    @State private var confirmDelete = false
    @State private var newItem = ""
    @State private var newSubtask = ""
    @State private var newTag = ""
    @State private var editingDate = false
    @FocusState private var focus: Field?

    private enum Field { case title, notes, item, subtask, tag }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                if task.trashedAt != nil { trashBanner }
                header
                properties
                Divider()
                notesEditor
                if task.kind == "CHECKLIST" || !tasks.items(of: task).isEmpty { checklist }
                subtasksSection
                footer
            }
            .padding(Theme.Space.xl)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { draft = DetailDraft(title: task.title, notes: currentNotes) }
        .onChange(of: task.title) { _, _ in follow() }
        .onChange(of: currentNotes) { _, _ in follow() }
        .onChange(of: focus) { old, _ in commit(old) }
        .onDisappear { commit(.title); commit(.notes) }
        .confirmationDialog("Delete \"\(task.title)\" permanently?", isPresented: $confirmDelete) {
            Button("Delete permanently", role: .destructive) { try? tasks.core.actions.deleteForever(id: task.id) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The task, its subtasks in Trash and its checklist are deleted on every device. This can't be undone.")
        }
    }

    // MARK: Sections

    private var trashBanner: some View {
        HStack {
            Label("In Trash", systemImage: "trash")
            Spacer()
            Button("Restore") { tasks.core.actions.restore(id: task.id) }
            Button("Delete permanently…", role: .destructive) { confirmDelete = true }
        }
        .padding(Theme.Space.m)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            CheckButton(task: task) { tasks.toggleComplete(task) }
            TextField("Title", text: $draft.title, axis: .vertical)
                .font(.title2.weight(.semibold))
                .textFieldStyle(.plain)
                .focused($focus, equals: .title)
                .onSubmit { commit(.title) }
        }
    }

    private var properties: some View {
        Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: Theme.Space.m, verticalSpacing: Theme.Space.s) {
            GridRow {
                Text("Date").foregroundStyle(.secondary)
                Button { editingDate = true } label: {
                    Label(dateLabel, systemImage: "calendar")
                }
                .buttonStyle(.borderless)
                .popover(isPresented: $editingDate) { DueEditor(task: task) }
            }
            GridRow {
                Text("Priority").foregroundStyle(.secondary)
                Menu {
                    ForEach(PriorityOption.all, id: \.value) { p in
                        Button(p.title) { tasks.core.actions.setPriority(id: task.id, priority: p.value) }
                    }
                } label: {
                    Label(PriorityOption.title(task.priority), systemImage: "flag.fill")
                        .foregroundStyle(Theme.PriorityColor.color(task.priority))
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            }
            GridRow {
                Text("List").foregroundStyle(.secondary)
                Menu(tasks.projectName(task.projectId)) {
                    Button("Inbox") { tasks.core.actions.move(id: task.id, projectId: "inbox") }
                    ForEach(tasks.listProjects, id: \.id) { p in
                        Button(p.name) { tasks.core.actions.move(id: task.id, projectId: p.id) }
                    }
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            }
            GridRow(alignment: .center) {
                Text("Tags").foregroundStyle(.secondary)
                HStack(spacing: Theme.Space.xs) {
                    ForEach(task.tags, id: \.self) { tag in
                        Button {
                            tasks.core.actions.setTags(id: task.id, tags: task.tags.filter { $0 != tag })
                        } label: {
                            TagPill(name: tag, color: tasks.tagColor(tag))
                        }
                        .buttonStyle(.plain)
                        .help("Remove tag")
                    }
                    TextField("Add tag", text: $newTag)
                        .textFieldStyle(.plain)
                        .frame(maxWidth: 120)
                        .focused($focus, equals: .tag)
                        .onSubmit { commit(.tag) }
                }
            }
            if let rule = task.repeatRule {
                GridRow {
                    Text("Repeat").foregroundStyle(.secondary)
                    Label(tasks.core.describeRepeat(rule: rule), systemImage: "repeat")
                }
            }
            if !task.reminders.isEmpty {
                GridRow {
                    Text("Reminders").foregroundStyle(.secondary)
                    Text(task.reminders.map(ReminderText.describe).joined(separator: ", "))
                }
            }
        }
    }

    private var notesEditor: some View {
        TextField("Notes", text: $draft.notes, axis: .vertical)
            .textFieldStyle(.plain)
            .lineLimit(3...)
            .focused($focus, equals: .notes)
    }

    private var checklist: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text("Checklist").font(.headline)
            ForEach(tasks.items(of: task), id: \.id) { item in
                HStack(spacing: Theme.Space.s) {
                    Button { tasks.core.actions.toggleItem(itemId: item.id) } label: {
                        Image(systemName: item.status == 0 ? "circle" : "checkmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                    Text(item.title)
                        .strikethrough(item.status != 0)
                        .foregroundStyle(item.status == 0 ? .primary : .secondary)
                    Spacer()
                    Button { tasks.core.actions.deleteItem(itemId: item.id) } label: { Image(systemName: "xmark") }
                        .buttonStyle(.borderless)
                        .help("Delete item")
                }
            }
            TextField("Add item", text: $newItem)
                .textFieldStyle(.plain)
                .focused($focus, equals: .item)
                .onSubmit { commit(.item); focus = .item }
        }
    }

    private var subtasksSection: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            let subs = tasks.subtasks(of: task)
            if !subs.isEmpty { Text("Subtasks").font(.headline) }
            ForEach(subs, id: \.id) { sub in
                HStack(spacing: Theme.Space.s) {
                    CheckButton(task: sub) { tasks.toggleComplete(sub) }
                    Text(sub.title).strikethrough(sub.isDone)
                }
            }
            if task.parentId == nil {
                TextField("Add subtask", text: $newSubtask)
                    .textFieldStyle(.plain)
                    .focused($focus, equals: .subtask)
                    .onSubmit { commit(.subtask); focus = .subtask }
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xxs) {
            if task.createdAt > 0 {
                Text("Created " + Date(timeIntervalSince1970: Double(task.createdAt) / 1000).formatted(date: .abbreviated, time: .shortened))
            }
            if let done = task.completedAt {
                Text("Completed " + Date(timeIntervalSince1970: Double(done.int64Value) / 1000).formatted(date: .abbreviated, time: .shortened))
            }
        }
        .font(.caption)
        .foregroundStyle(.tertiary)
    }

    private var currentNotes: String { task.content.isEmpty ? task.desc : task.content }

    private func follow() {
        let editing: DetailDraft.Field? = switch focus { case .title: .title; case .notes: .notes; default: nil }
        draft.follow(title: task.title, notes: currentNotes, editing: editing)
    }

    private var dateLabel: String {
        guard let day = tasks.day(of: task) else { return "Add date" }
        return DateText.short(day: day, task: task)
    }

    // MARK: Commit

    private func commit(_ field: Field?) {
        let actions = tasks.core.actions
        switch field {
        case .title:
            if let t = draft.titleToSave(current: task.title) { actions.rename(id: task.id, title: t) }
            draft.saved(.title)
        case .notes:
            if let n = draft.notesToSave(current: currentNotes) { actions.setContent(id: task.id, content: n) }
            draft.saved(.notes)
        case .item:
            let t = newItem.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty { _ = actions.addItem(taskId: task.id, title: t); newItem = "" }
        case .subtask:
            let t = newSubtask.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty { _ = try? actions.addSubtask(parentId: task.id, title: t); newSubtask = "" }
        case .tag:
            let t = newTag.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty { actions.setTags(id: task.id, tags: task.tags + [t]); newTag = "" }
        case nil:
            break
        }
    }
}

/// Date popover: a calendar, an optional time, and Clear.
private struct DueEditor: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    let task: ZTask
    @State private var date = Date()
    @State private var hasTime = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            DatePicker("Date", selection: $date, displayedComponents: [.date])
                .datePickerStyle(.graphical)
                .labelsHidden()
            Toggle("Time", isOn: $hasTime)
            if hasTime {
                DatePicker("Time", selection: $date, displayedComponents: [.hourAndMinute]).labelsHidden()
            }
            HStack {
                Button("Clear") {
                    tasks.core.actions.setDue(id: task.id, due: nil, allDay: false)
                    dismiss()
                }
                Spacer()
                Button("Done") { save(); dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(Theme.Space.l)
        .frame(width: 280)
        .onAppear {
            if let ms = task.startDate ?? task.dueDate { date = Date(timeIntervalSince1970: Double(ms.int64Value) / 1000) }
            hasTime = !task.isAllDay && (task.dueDate != nil || task.startDate != nil)
        }
    }

    private func save() {
        let cal = Calendar.current
        let value = hasTime ? date : cal.startOfDay(for: date)
        tasks.core.actions.setDue(id: task.id, due: KotlinLong(value: Int64(value.timeIntervalSince1970 * 1000)), allDay: !hasTime)
    }
}

enum ReminderText {
    /// `TRIGGER:-PT15M` → "15 min before"; `TRIGGER:PT0S` → "On time".
    static func describe(_ trigger: String) -> String {
        let t = trigger.replacingOccurrences(of: "TRIGGER:", with: "")
        if t == "PT0S" || t == "P0D" { return "On time" }
        let before = t.hasPrefix("-")
        var parts: [String] = []
        let scanner = t.replacingOccurrences(of: "-", with: "")
        let units: [(Character, String)] = [("W", "w"), ("D", "d"), ("H", "h"), ("M", "min")]
        var number = ""
        var inTime = false
        for c in scanner {
            if c == "P" { continue }
            if c == "T" { inTime = true; continue }
            if c.isNumber { number.append(c); continue }
            if c == "S" { number = ""; continue }
            if let unit = units.first(where: { $0.0 == c }) {
                let label = (c == "M" && !inTime) ? "mo" : unit.1
                if let n = Int(number), n > 0 { parts.append("\(n) \(label)") }
                number = ""
            }
        }
        if parts.isEmpty { return "On time" }
        return parts.joined(separator: " ") + (before ? " before" : " after")
    }
}

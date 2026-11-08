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
    @State private var pickingPriority = false
    @State private var pickingList = false
    @FocusState private var focus: Field?

    private enum Field { case title, notes, item, subtask, tag }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                if task.trashedAt != nil { trashBanner }
                header
                chips
                card { notesEditor }
                if task.kind == "CHECKLIST" || !tasks.items(of: task).isEmpty { card { checklist } }
                card { subtasksSection }
                footer
            }
            .padding(Theme.Space.xl)
            .frame(maxWidth: Theme.Detail.maxWidth, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear {
            draft = DetailDraft(title: task.title, notes: currentNotes)
            takeDateRequest()
        }
        .onChange(of: tasks.requestDateEdit) { _, _ in takeDateRequest() }
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
        .controlSize(.large)
        .padding(Theme.Detail.cardPadding)
        .background(Color.orange.opacity(Theme.Opacity.chipFill), in: RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous))
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.m) {
            CheckButton(task: task) { tasks.toggleComplete(task) }
                .font(.title2)
            TextField("Title", text: $draft.title, axis: .vertical)
                .font(.title2.weight(.semibold))
                .textFieldStyle(.plain)
                .focused($focus, equals: .title)
                .onSubmit { commit(.title) }
        }
    }

    /// Date, priority, list, repeat and tags as coloured chips: each one is a button or menu.
    private var chips: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.s) {
                Button { editingDate = true } label: {
                    Chip(text: dateLabel, symbol: "calendar", tint: dateTint, filled: tasks.day(of: task) != nil)
                }
                .buttonStyle(.plain)
                .popover(isPresented: $editingDate) { DueEditor(task: task) }

                Button { pickingPriority = true } label: {
                    Chip(text: PriorityOption.title(task.priority), symbol: "flag.fill", tint: Theme.PriorityColor.color(task.priority), filled: task.priority > 0)
                }
                .buttonStyle(.plain)
                .popover(isPresented: $pickingPriority) {
                    PickerList(items: PriorityOption.all.map { ($0.title, "flag.fill", Theme.PriorityColor.color($0.value), $0.value == task.priority) }) { i in
                        tasks.core.actions.setPriority(id: task.id, priority: PriorityOption.all[i].value)
                        pickingPriority = false
                    }
                }

                Button { pickingList = true } label: {
                    Chip(text: tasks.projectName(task.projectId), symbol: "list.bullet", tint: listTint, filled: true)
                }
                .buttonStyle(.plain)
                .popover(isPresented: $pickingList) {
                    let targets = [("inbox", "Inbox", Color.accentColor)] + tasks.listProjects.map { ($0.id, $0.name, Color(hex: $0.color) ?? .accentColor) }
                    PickerList(items: targets.map { ($0.1, "list.bullet", $0.2, $0.0 == task.projectId) }) { i in
                        tasks.core.actions.move(id: task.id, projectId: targets[i].0)
                        pickingList = false
                    }
                }

                if let rule = task.repeatRule {
                    Chip(text: tasks.core.describeRepeat(rule: rule), symbol: "repeat", tint: .accentColor, filled: true)
                }
            }
            HStack(spacing: Theme.Space.s) {
                ForEach(task.tags, id: \.self) { tag in
                    Button {
                        tasks.core.actions.setTags(id: task.id, tags: task.tags.filter { $0 != tag })
                    } label: {
                        Chip(text: "#" + tag, symbol: nil, tint: Color(hex: tasks.tagColor(tag)) ?? .accentColor, filled: true)
                    }
                    .buttonStyle(.plain)
                    .help("Remove tag")
                }
                HStack(spacing: Theme.Space.xs) {
                    Image(systemName: "number").foregroundStyle(.secondary)
                    TextField("Add tag", text: $newTag)
                        .textFieldStyle(.plain)
                        .frame(width: Theme.Size.tagField)
                        .focused($focus, equals: .tag)
                        .onSubmit { commit(.tag) }
                }
                .padding(.horizontal, Theme.Space.m)
                .frame(height: Theme.Detail.chipHeight)
                .overlay(Capsule().strokeBorder(.quaternary))
            }
            if !task.reminders.isEmpty {
                Label(task.reminders.map(ReminderText.describe).joined(separator: ", "), systemImage: "bell")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var dateTint: Color {
        guard tasks.day(of: task) != nil else { return .secondary }
        if let day = tasks.day(of: task), !task.isDone, day < Calendar.current.startOfDay(for: .now) { return .red }
        return .accentColor
    }

    private var listTint: Color {
        Color(hex: tasks.projects.first { $0.id == task.projectId }?.color) ?? .accentColor
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Theme.Detail.cardPadding)
            .background(.quaternary.opacity(Theme.Opacity.card), in: RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous))
    }

    private var notesEditor: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Label("Notes", systemImage: "text.alignleft").font(.headline).foregroundStyle(.secondary)
            TextField("Write notes…", text: $draft.notes, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(3...)
                .focused($focus, equals: .notes)
        }
    }

    private var checklist: some View {
        let items = tasks.items(of: task)
        let done = items.filter { $0.status != 0 }.count
        return VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack {
                Label("Checklist", systemImage: "checklist").font(.headline).foregroundStyle(.secondary)
                Spacer()
                if !items.isEmpty { Text("\(done)/\(items.count)").font(.callout).foregroundStyle(.secondary).monospacedDigit() }
            }
            if !items.isEmpty {
                ProgressView(value: Double(done), total: Double(items.count)).tint(.accentColor)
            }
            ForEach(items, id: \.id) { item in
                HStack(spacing: Theme.Space.m) {
                    Button { tasks.core.actions.toggleItem(itemId: item.id) } label: {
                        Image(systemName: item.status == 0 ? "circle" : "checkmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(item.status == 0 ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
                    }
                    .buttonStyle(.plain)
                    Text(item.title)
                        .strikethrough(item.status != 0)
                        .foregroundStyle(item.status == 0 ? .primary : .secondary)
                    Spacer()
                    Button { tasks.core.actions.deleteItem(itemId: item.id) } label: { Image(systemName: "xmark") }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.tertiary)
                        .help("Delete item")
                }
                .padding(.vertical, Theme.Space.xxs)
            }
            HStack(spacing: Theme.Space.m) {
                Image(systemName: "plus").foregroundStyle(.secondary)
                TextField("Add item", text: $newItem)
                    .textFieldStyle(.plain)
                    .focused($focus, equals: .item)
                    .onSubmit { commit(.item); focus = .item }
            }
        }
    }

    private var subtasksSection: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            let subs = tasks.subtasks(of: task)
            Label("Subtasks", systemImage: "list.bullet.indent").font(.headline).foregroundStyle(.secondary)
            ForEach(subs, id: \.id) { sub in
                HStack(spacing: Theme.Space.m) {
                    CheckButton(task: sub) { tasks.toggleComplete(sub) }
                    Text(sub.title).strikethrough(sub.isDone).foregroundStyle(sub.isDone ? .secondary : .primary)
                    Spacer()
                }
                .padding(.vertical, Theme.Space.xxs)
                .contentShape(Rectangle())
                .onTapGesture { tasks.selection = sub.id }
            }
            if task.parentId == nil {
                HStack(spacing: Theme.Space.m) {
                    Image(systemName: "plus").foregroundStyle(.secondary)
                    TextField("Add subtask", text: $newSubtask)
                        .textFieldStyle(.plain)
                        .focused($focus, equals: .subtask)
                        .onSubmit { commit(.subtask); focus = .subtask }
                }
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

    private func takeDateRequest() {
        guard tasks.requestDateEdit == task.id else { return }
        tasks.requestDateEdit = nil
        editingDate = true
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

/// A rounded, tinted property chip (date, priority, list, tag).
struct Chip: View {
    let text: String
    let symbol: String?
    let tint: Color
    let filled: Bool

    var body: some View {
        HStack(spacing: Theme.Space.xs) {
            if let symbol { Image(systemName: symbol).foregroundStyle(tint) }
            Text(text).foregroundStyle(filled ? .primary : .secondary).lineLimit(1)
        }
        .font(.callout.weight(.medium))
        .padding(.horizontal, Theme.Space.m)
        .frame(height: Theme.Detail.chipHeight)
        .background(filled ? AnyShapeStyle(tint.opacity(Theme.Opacity.chipFill)) : AnyShapeStyle(.clear), in: Capsule())
        .overlay(Capsule().strokeBorder(filled ? AnyShapeStyle(.clear) : AnyShapeStyle(.quaternary)))
        .contentShape(Capsule())
    }
}

/// A popover list of choices with large rows (priority, list).
struct PickerList: View {
    let items: [(title: String, symbol: String, tint: Color, selected: Bool)]
    let choose: (Int) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                    Button { choose(i) } label: {
                        HStack(spacing: Theme.Space.m) {
                            Image(systemName: item.symbol).foregroundStyle(item.tint).frame(width: Theme.Space.l)
                            Text(item.title).lineLimit(1)
                            Spacer()
                            if item.selected { Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                        .padding(.horizontal, Theme.Space.m)
                        .frame(height: Theme.Size.minRow)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(Theme.Space.s)
        }
        .frame(width: Theme.Size.pickerWidth)
        .frame(maxHeight: Theme.Size.pickerMaxHeight)
    }
}

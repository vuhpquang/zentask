import AppKit
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
    /// Shown at once when picked (the synced value follows after the index reloads).
    @State private var pickedPriority: Int32?
    @State private var pickedList: String?
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
                card { AttachmentsSection(task: task) }
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
        .onChange(of: task.priority) { _, _ in pickedPriority = nil }
        .onChange(of: task.projectId) { _, _ in pickedList = nil }
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
            if task.pinnedAt != nil { Image(systemName: "pin.fill").foregroundStyle(Theme.Habits.gold).help("Pinned") }
            if task.trashedAt == nil { TaskMoreMenu(task: task) { focus = .subtask } }
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
                    let p = pickedPriority ?? task.priority
                    Chip(text: PriorityOption.title(p), symbol: "flag.fill", tint: Theme.PriorityColor.color(p), filled: p > 0)
                }
                .buttonStyle(.plain)
                .popover(isPresented: $pickingPriority) {
                    PickerList(items: PriorityOption.all.map { ($0.title, "flag.fill", Theme.PriorityColor.color($0.value), $0.value == (pickedPriority ?? task.priority)) }) { i in
                        pickedPriority = PriorityOption.all[i].value
                        pickingPriority = false
                        tasks.core.actions.setPriority(id: task.id, priority: PriorityOption.all[i].value)
                    }
                }

                Button { pickingList = true } label: {
                    Chip(text: tasks.projectName(pickedList ?? task.projectId), symbol: "list.bullet", tint: listTint, filled: true)
                }
                .buttonStyle(.plain)
                .popover(isPresented: $pickingList) {
                    let targets = [("inbox", "Inbox", Color.themeAccent)] + tasks.listProjects.map { ($0.id, $0.name, Color(hex: $0.color) ?? .themeAccent) }
                    PickerList(items: targets.map { ($0.1, "list.bullet", $0.2, $0.0 == (pickedList ?? task.projectId)) }) { i in
                        pickedList = targets[i].0
                        pickingList = false
                        tasks.core.actions.move(id: task.id, projectId: targets[i].0)
                    }
                }

                LocationChip(task: task)

                if let rule = task.repeatRule {
                    Button { editingDate = true } label: {
                        Chip(text: tasks.core.describeRepeat(rule: rule), symbol: "repeat", tint: .themeAccent, filled: true)
                    }
                    .buttonStyle(.plain)
                }
            }
            HStack(spacing: Theme.Space.s) {
                ForEach(task.tags, id: \.self) { tag in
                    Button {
                        tasks.core.actions.setTags(id: task.id, tags: task.tags.filter { $0 != tag })
                    } label: {
                        Chip(text: "#" + tag, symbol: nil, tint: Color(hex: tasks.tagColor(tag)) ?? .themeAccent, filled: true)
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
        return .themeAccent
    }

    private var listTint: Color {
        Color(hex: tasks.projects.first { $0.id == task.projectId }?.color) ?? .themeAccent
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
            if focus != .notes && MarkdownLite.shared.hasMarkdown(text: draft.notes) {
                // Markdown shown formatted; a click edits the text, a to-do box ticks in place.
                MarkdownNotes(text: draft.notes, toggle: { i in
                    draft.notes = MarkdownLite.shared.toggleTodo(text: draft.notes, index: Int32(i))
                    commit(.notes)
                })
                .contentShape(Rectangle())
                .onTapGesture { focus = .notes }
                .help("Click to edit")
            } else {
                // A text editor, not a TextField: Return makes a new line (a TextField ends editing on Return).
                TextEditor(text: $draft.notes)
                    .font(.body)
                    .scrollContentBackground(.hidden)
                    .scrollDisabled(true)
                    .frame(minHeight: Theme.Detail.notesMinHeight)
                    .focused($focus, equals: .notes)
                    .overlay(alignment: .topLeading) {
                        if draft.notes.isEmpty {
                            Text("Write notes… (Markdown works: **bold**, - lists, - [ ] to-dos)")
                                .foregroundStyle(.tertiary)
                                .padding(.leading, Theme.Space.xxs)
                                .allowsHitTesting(false)
                        }
                    }
            }
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
                ProgressView(value: Double(done), total: Double(items.count)).tint(.themeAccent)
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

/// Date popover: quick picks (today, tomorrow, next week, tonight), a month, an optional time, reminders,
/// repeat, then Clear or OK.
struct DueEditor: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    let task: ZTask
    @State private var date = Date()
    @State private var hasTime = false
    @State private var reminders: [String] = []
    @State private var rule: String?
    /// A repeat chosen from the menu: rebuilt from the date on save, so moving the date moves the weekday too.
    @State private var preset: Int?
    @State private var original: (date: Date, hasTime: Bool)?
    /// Date (one moment or day) or Duration (start and end), like TickTick's two tabs.
    @State private var durationTab = false
    @State private var rangeStart = Date()
    @State private var rangeEnd = Date().addingTimeInterval(3600)

    private var cal: Calendar { Calendar.current }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Picker("Kind", selection: $durationTab) {
                Text("Date").tag(false)
                Text("Duration").tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if durationTab { durationEditor } else { dateEditor }
            HStack {
                Button("Clear") {
                    tasks.core.actions.setDue(id: task.id, due: nil, allDay: false)
                    tasks.core.actions.setReminders(id: task.id, triggers: [])
                    tasks.core.actions.setRepeat(id: task.id, rule: nil, from: nil)
                    dismiss()
                }
                .frame(maxWidth: .infinity)
                Button("OK") { durationTab ? saveRange() : save(); dismiss() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.l)
        .frame(width: Theme.Detail.datePopoverWidth)
        .onAppear(perform: load)
    }

    /// Start and end, all day or with times.
    private var durationEditor: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Toggle("All day", isOn: Binding(get: { !hasTime }, set: { hasTime = !$0 }))
            DatePicker("Start", selection: $rangeStart, displayedComponents: hasTime ? [.date, .hourAndMinute] : [.date])
                .onChange(of: rangeStart) { old, new in
                    // Keep the length when the start moves.
                    rangeEnd = rangeEnd.addingTimeInterval(new.timeIntervalSince(old))
                }
            DatePicker("End", selection: $rangeEnd, in: rangeStart..., displayedComponents: hasTime ? [.date, .hourAndMinute] : [.date])
            Text(lengthText).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var lengthText: String {
        let f = DateComponentsFormatter()
        f.allowedUnits = hasTime ? [.day, .hour, .minute] : [.day]
        f.unitsStyle = .full
        let seconds = hasTime ? rangeEnd.timeIntervalSince(rangeStart) : rangeEnd.timeIntervalSince(rangeStart) + 86_400
        return f.string(from: max(0, seconds)) ?? ""
    }

    private func saveRange() {
        let actions = tasks.core.actions
        if hasTime {
            actions.setSpan(id: task.id, start: Int64(rangeStart.timeIntervalSince1970 * 1000), end: Int64(rangeEnd.timeIntervalSince1970 * 1000))
        } else {
            actions.setAllDaySpan(id: task.id, startDay: Int64(cal.startOfDay(for: rangeStart).timeIntervalSince1970 * 1000),
                                  lastDay: Int64(cal.startOfDay(for: rangeEnd).timeIntervalSince1970 * 1000))
        }
        if reminders != task.reminders { actions.setReminders(id: task.id, triggers: reminders) }
        let finalRule = preset.flatMap { repeatChoices.indices.contains($0) ? repeatChoices[$0].0 : nil } ?? rule
        if finalRule != task.repeatRule { actions.setRepeat(id: task.id, rule: finalRule, from: task.repeatFrom ?? "due") }
    }

    private var dateEditor: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            HStack(spacing: Theme.Space.s) {
                quick("sun.max", "Today") { set(day: 0) }
                quick("sunrise", "Tomorrow") { set(day: 1) }
                quick("calendar.badge.plus", "Next Monday") { set(day: daysToNextMonday) }
                quick("moon.stars", "Tonight at 20:00") { set(day: 0, hour: 20) }
            }
            MonthPicker(selection: $date)
            Divider()
            HStack {
                Label("Time", systemImage: "clock")
                Spacer()
                if hasTime {
                    DatePicker("Time", selection: $date, displayedComponents: [.hourAndMinute]).labelsHidden().fixedSize()
                }
                Toggle("Time", isOn: $hasTime).labelsHidden().toggleStyle(.switch).controlSize(.small)
                    .onChange(of: hasTime) { _, timed in
                        // Keep reminders meaning the same thing (on time ↔ 9:00 on the day…); drop ones that can't.
                        reminders = ReminderPresets.shared.convert(triggers: reminders, toAllDay: !timed)
                    }
            }
            row("Reminder", symbol: "bell", value: reminders.isEmpty ? "None" : reminders.map(ReminderText.describe).joined(separator: ", ")) {
                Button("None") { reminders = [] }
                Divider()
                let presets = (hasTime ? ReminderPresets.shared.timed : ReminderPresets.shared.allDay).map { ($0.first as String? ?? "", $0.second as String? ?? "") }
                // Existing reminders that aren't presets stay listed so they can be turned off.
                let others = reminders.filter { r in !presets.contains { $0.0 == r } }.map { ($0, ReminderText.describe($0)) }
                ForEach(presets + others, id: \.0) { trigger, title in
                    Toggle(title, isOn: Binding(get: { reminders.contains(trigger) }, set: { on in
                        if on { reminders.append(trigger) } else { reminders.removeAll { $0 == trigger } }
                    }))
                }
            }
            row("Repeat", symbol: "repeat", value: repeatTitle) {
                Button("None") { rule = nil; preset = nil }
                Divider()
                ForEach(Array(repeatChoices.enumerated()), id: \.offset) { i, choice in
                    Button { preset = i; rule = choice.0 } label: { if preset == i { Label(choice.1, systemImage: "checkmark") } else { Text(choice.1) } }
                }
            }
        }
    }

    private func load() {
            if let ms = task.startDate ?? task.dueDate { date = Date(timeIntervalSince1970: Double(ms.int64Value) / 1000) }
            hasTime = !task.isAllDay && (task.dueDate != nil || task.startDate != nil)
            reminders = task.reminders
            rule = task.repeatRule
            preset = repeatChoices.firstIndex { $0.0 == task.repeatRule }
            original = (date, hasTime)
        if let start = task.startDate, let due = task.dueDate, due.int64Value > start.int64Value {
            durationTab = true
            rangeStart = Date(timeIntervalSince1970: Double(start.int64Value) / 1000)
            rangeEnd = Date(timeIntervalSince1970: Double(due.int64Value) / 1000)
            // All-day ranges are stored up to the midnight after the last day; show the last day.
            if task.isAllDay { rangeEnd = cal.date(byAdding: .day, value: -1, to: rangeEnd) ?? rangeEnd }
        } else {
            rangeStart = date
            rangeEnd = hasTime ? date.addingTimeInterval(3600) : date
        }
    }

    private var repeatTitle: String {
        if let preset, repeatChoices.indices.contains(preset) { return repeatChoices[preset].1 }
        return rule.map { tasks.core.describeRepeat(rule: $0) } ?? "None"
    }

    private var daysToNextMonday: Int {
        let weekday = cal.component(.weekday, from: .now) // 1 = Sunday
        return (9 - weekday) % 7 == 0 ? 7 : (9 - weekday) % 7
    }

    private func set(day offset: Int, hour: Int? = nil) {
        let base = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: .now)) ?? .now
        if let hour {
            let evening = cal.date(bySettingHour: hour, minute: 0, second: 0, of: base) ?? base
            // "Tonight" after 20:00 means the next full hour, never a time already past.
            date = evening > .now ? evening : (cal.nextDate(after: .now, matching: DateComponents(minute: 0), matchingPolicy: .nextTime) ?? evening)
            hasTime = true
        } else {
            date = hasTime ? (cal.date(bySettingHour: cal.component(.hour, from: date), minute: cal.component(.minute, from: date), second: 0, of: base) ?? base) : base
        }
    }

    private func quick(_ symbol: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.title3).frame(maxWidth: .infinity, minHeight: Theme.Detail.chipHeight)
        }
        .buttonStyle(.bordered)
        .help(title)
        .accessibilityLabel(title)
    }

    private func row<M: View>(_ title: String, symbol: String, value: String, @ViewBuilder menu: () -> M) -> some View {
        HStack {
            Label(title, systemImage: symbol)
            Spacer()
            Menu { menu() } label: { Text(value).lineLimit(1) }
                .menuStyle(.borderlessButton)
                .fixedSize()
        }
    }

    private var repeatChoices: [(String, String)] {
        let codes = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"]
        let wd = cal.component(.weekday, from: date)
        let weekday = date.formatted(.dateTime.weekday(.wide))
        let day = cal.component(.day, from: date)
        return [
            ("RRULE:FREQ=DAILY;INTERVAL=1", "Daily"),
            ("RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR", "Every weekday (Mon–Fri)"),
            ("RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=\(codes[wd - 1])", "Weekly on \(weekday)"),
            ("RRULE:FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=\(day)", "Monthly on day \(day)"),
            ("RRULE:FREQ=YEARLY;INTERVAL=1", "Yearly on \(date.formatted(.dateTime.day().month(.wide)))"),
        ]
    }

    private func save() {
        let value = hasTime ? date : cal.startOfDay(for: date)
        let actions = tasks.core.actions
        let hadDate = task.startDate != nil || task.dueDate != nil
        // Only touch the date when it changed: setDue clears a start–end range.
        if !hadDate || original.map({ $0.date != date || $0.hasTime != hasTime }) ?? true {
            actions.setDue(id: task.id, due: KotlinLong(value: Int64(value.timeIntervalSince1970 * 1000)), allDay: !hasTime)
        }
        if reminders != task.reminders { actions.setReminders(id: task.id, triggers: reminders) }
        let finalRule = preset.flatMap { repeatChoices.indices.contains($0) ? repeatChoices[$0].0 : nil } ?? rule
        if finalRule != task.repeatRule { actions.setRepeat(id: task.id, rule: finalRule, from: task.repeatFrom ?? "due") }
    }
}

/// "…" in the task detail: the actions TickTick keeps there.
struct TaskMoreMenu: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.startFocus) private var startFocus
    let task: ZTask
    let addSubtask: () -> Void

    var body: some View {
        Menu {
            if task.parentId == nil { Button("Add subtask", systemImage: "list.bullet.indent", action: addSubtask) }
            if task.pinnedAt == nil {
                Button("Pin", systemImage: "pin") { tasks.core.actions.pin(id: task.id) }
            } else {
                Button("Unpin", systemImage: "pin.slash") { tasks.core.actions.unpin(id: task.id) }
            }
            if task.status == 0 { Button("Won't do", systemImage: "xmark.circle") { tasks.core.actions.wontDo(id: task.id) } }
            if task.status == 0 { Button("Start focus", systemImage: "timer") { startFocus(task.id) } }
            Divider()
            Button("Duplicate", systemImage: "plus.square.on.square") { tasks.selection = tasks.core.actions.duplicate(id: task.id).id }
            Button("Copy link", systemImage: "link") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString("zentask://task/\(task.id)", forType: .string)
            }
            Divider()
            Button("Move to Trash", systemImage: "trash", role: .destructive) { tasks.core.actions.trash(id: task.id) }
        } label: {
            Image(systemName: "ellipsis.circle").font(.title2)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("More")
    }
}

enum ReminderText {
    /// `TRIGGER:-PT15M` → "15 min before"; `TRIGGER:PT0S` → "On time".
    static func describe(_ trigger: String) -> String {
        if let preset = ReminderPresets.shared.title(trigger: trigger) { return preset }
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

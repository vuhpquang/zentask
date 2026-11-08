import SwiftUI
@preconcurrency import ZenTaskShared

/// What a new task starts with: a day (all day), or a time range dragged out on the calendar.
struct ComposerStart: Equatable {
    var day: Date?
    var start: Date?
    var end: Date?

    static func allDay(_ day: Date) -> ComposerStart { ComposerStart(day: day) }
    static func span(_ start: Date, _ end: Date) -> ComposerStart { ComposerStart(day: nil, start: start, end: end) }
}

/// The new-task card (calendar "+", a range dragged on the calendar, the global Quick Add): date and priority
/// on top, the title (typing `~` picks a list, `!` a priority, `#` a tag; dates like "tomorrow 9am" work too),
/// notes, and the list at the bottom. Return adds, Esc cancels.
struct TaskComposer: View {
    @Environment(TasksModel.self) private var tasks
    let start: ComposerStart?
    let done: () -> Void

    @State private var title = ""
    @State private var notes = ""
    @State private var listId: String?
    @State private var priority: Int32 = 0
    @State private var tags: [String] = []
    @State private var picking: Picker?
    @FocusState private var titleFocused: Bool

    enum Picker: Identifiable { case list, priority, tag; var id: Self { self } }

    var body: some View {
        let parsed = tasks.parse(title)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.Space.s) {
                Label(dateText(parsed), systemImage: "calendar")
                    .foregroundStyle(hasDate(parsed) ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    .font(.callout.weight(.medium))
                Spacer()
                Menu {
                    ForEach(PriorityOption.all, id: \.value) { p in
                        Button { priority = p.value } label: { Label(p.title, systemImage: priority == p.value ? "checkmark" : "flag") }
                    }
                } label: {
                    Image(systemName: priority > 0 ? "flag.fill" : "flag")
                        .foregroundStyle(Theme.PriorityColor.color(priority))
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Priority (or type !)")
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.m)
            Divider()
            VStack(alignment: .leading, spacing: Theme.Space.s) {
                TextField("What would you like to do?", text: $title, axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(.title3.weight(.medium))
                    .focused($titleFocused)
                    .onSubmit(add)
                    .onChange(of: title) { old, new in trigger(old, new) }
                    .popover(item: $picking, arrowEdge: .bottom) { which in pickerView(which) }
                TextField("Notes", text: $notes, axis: .vertical)
                    .textFieldStyle(.plain)
                    .foregroundStyle(.secondary)
                    .lineLimit(2...6)
                if !tags.isEmpty || !parsed.tags.isEmpty {
                    HStack(spacing: Theme.Space.xs) {
                        ForEach(Array(Set(tags + parsed.tags)).sorted(), id: \.self) { t in
                            TagPill(name: t, color: tasks.tagColor(t))
                        }
                    }
                }
            }
            .padding(Theme.Space.l)
            .frame(minHeight: Theme.Composer.bodyHeight, alignment: .top)
            Divider()
            HStack(spacing: Theme.Space.m) {
                Menu {
                    ForEach(listChoices, id: \.0) { id, name in
                        Button { listId = id } label: { Label(name, systemImage: (listId ?? "inbox") == id ? "checkmark" : "list.bullet") }
                    }
                } label: {
                    Label(listName(parsed), systemImage: "tray")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .help("List (or type ~)")
                Spacer()
                Button("Cancel", role: .cancel, action: done).keyboardShortcut(.cancelAction)
                Button("Add", action: add)
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(parsed.title.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.m)
        }
        .frame(width: Theme.Composer.width)
        .onAppear { titleFocused = true }
    }

    // MARK: Triggers

    /// A `~`, `!` or `#` typed at the start or after a space opens its picker; the character is removed.
    private func trigger(_ old: String, _ new: String) {
        guard new.count == old.count + 1, let last = new.last, ["~", "!", "#"].contains(last) else { return }
        let before = new.dropLast()
        guard before.isEmpty || before.last == " " else { return }
        title = String(before)
        picking = last == "~" ? .list : last == "!" ? .priority : .tag
    }

    @ViewBuilder
    private func pickerView(_ which: Picker) -> some View {
        switch which {
        case .list:
            PickerList(items: listChoices.map { choice in
                (choice.1, "list.bullet", Color(hex: tasks.projects.first { $0.id == choice.0 }?.color) ?? .accentColor, (listId ?? "inbox") == choice.0)
            }) { i in
                listId = listChoices[i].0
                picking = nil
                titleFocused = true
            }
        case .priority:
            PickerList(items: PriorityOption.all.map { ($0.title, "flag.fill", Theme.PriorityColor.color($0.value), $0.value == priority) }) { i in
                priority = PriorityOption.all[i].value
                picking = nil
                titleFocused = true
            }
        case .tag:
            PickerList(items: tasks.tags.map { ("#" + ($0.label.isEmpty ? $0.name : $0.label), "number", Color(hex: $0.color) ?? .accentColor, tags.contains($0.name)) }) { i in
                let name = tasks.tags[i].name
                if !tags.contains(name) { tags.append(name) }
                picking = nil
                titleFocused = true
            }
        }
    }

    // MARK: Values

    private var listChoices: [(String, String)] {
        [("inbox", "Inbox")] + tasks.listProjects.map { ($0.id, $0.name) }
    }

    private func listName(_ parsed: QuickAdd) -> String {
        let id = parsed.projectId ?? listId ?? "inbox"
        return listChoices.first { $0.0 == id }?.1 ?? "Inbox"
    }

    private func hasDate(_ parsed: QuickAdd) -> Bool { parsed.due != nil || start != nil }

    private func dateText(_ parsed: QuickAdd) -> String {
        if let due = parsed.due {
            let d = Date(timeIntervalSince1970: Double(due.int64Value) / 1000)
            return parsed.allDay ? d.formatted(.dateTime.weekday(.abbreviated).day().month()) : d.formatted(.dateTime.weekday(.abbreviated).day().month().hour().minute())
        }
        if let s = start?.start, let e = start?.end {
            return s.formatted(.dateTime.weekday(.abbreviated).day().month()) + ", " + s.formatted(date: .omitted, time: .shortened) + " – " + e.formatted(date: .omitted, time: .shortened)
        }
        if let d = start?.day { return d.formatted(.dateTime.weekday(.wide).day().month()) }
        return "No date"
    }

    private func add() {
        let parsed = tasks.parse(title)
        let clean = parsed.title.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else { return }
        let actions = tasks.core.actions
        let millis = { (d: Date) in KotlinLong(value: Int64(d.timeIntervalSince1970 * 1000)) }
        let due: KotlinLong? = parsed.due ?? start?.day.map { millis(Calendar.current.startOfDay(for: $0)) }
        let allDay = parsed.due != nil ? parsed.allDay : true
        let p = parsed.priority?.int32Value ?? priority
        let task = actions.create(title: clean, filter: nil, projectId: parsed.projectId ?? listId, priority: p > 0 ? KotlinInt(int: p) : nil,
                                  tags: Array(Set(tags + parsed.tags)), due: due, allDay: allDay)
        if parsed.due == nil, let s = start?.start, let e = start?.end {
            actions.setSpan(id: task.id, start: millis(s).int64Value, end: millis(e).int64Value)
        }
        let n = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        if !n.isEmpty { actions.setContent(id: task.id, content: n) }
        done()
    }
}

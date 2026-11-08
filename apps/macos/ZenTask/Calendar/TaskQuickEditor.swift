import SwiftUI
@preconcurrency import ZenTaskShared

/// The card a task opens on the calendar: check it, change its date and priority, edit the title and notes in
/// place, move it to another list, open it in Tasks. Follows the live task, so edits elsewhere show up.
struct TaskQuickEditor: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.openTask) private var openTask
    let taskId: String

    @State private var title = ""
    @State private var notes = ""
    /// What the card opened with: only fields changed from these are written (newer synced edits stay).
    @State private var openedTitle = ""
    @State private var openedNotes = ""
    @State private var editingDate = false
    @State private var priority: Int32?
    @FocusState private var focus: Field?

    private enum Field { case title, notes }

    var body: some View {
        if let task = tasks.index?.task(id: taskId) {
            card(task)
        } else {
            Text("This task no longer exists").foregroundStyle(.secondary).padding(Theme.Space.l)
        }
    }

    private func card(_ task: ZTask) -> some View {
        let p = priority ?? task.priority
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.Space.m) {
                CheckButton(task: task) { tasks.toggleComplete(task) }
                Divider().frame(height: Theme.Space.l)
                Button { editingDate = true } label: {
                    Label(dateText(task), systemImage: "calendar")
                        .foregroundStyle(dateTint(task))
                        .font(.callout.weight(.medium))
                }
                .buttonStyle(.plain)
                .popover(isPresented: $editingDate) { DueEditor(task: task) }
                Spacer()
                Menu {
                    ForEach(PriorityOption.all, id: \.value) { option in
                        Button {
                            priority = option.value
                            tasks.core.actions.setPriority(id: task.id, priority: option.value)
                        } label: { Label(option.title, systemImage: option.value == p ? "checkmark" : "flag") }
                    }
                } label: {
                    Image(systemName: p > 0 ? "flag.fill" : "flag").foregroundStyle(Theme.PriorityColor.color(p))
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Priority")
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.m)
            Divider()
            VStack(alignment: .leading, spacing: Theme.Space.m) {
                TextField("Title", text: $title, axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(.title3.weight(.semibold))
                    .focused($focus, equals: .title)
                    .onSubmit { commit(task) }
                TextField("Notes", text: $notes, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(3...14)
                    .focused($focus, equals: .notes)
            }
            .padding(Theme.Space.l)
            .frame(minHeight: Theme.Composer.bodyHeight, alignment: .top)
            Divider()
            HStack(spacing: Theme.Space.m) {
                Menu {
                    Button("Inbox") { tasks.core.actions.move(id: task.id, projectId: "inbox") }
                    ForEach(tasks.listProjects, id: \.id) { list in
                        Button(list.name) { tasks.core.actions.move(id: task.id, projectId: list.id) }
                    }
                } label: {
                    Label(tasks.projectName(task.projectId), systemImage: "tray")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                Spacer()
                Button { commit(task); openTask(task.id) } label: { Image(systemName: "arrow.up.forward.square") }
                    .buttonStyle(.borderless)
                    .help("Open in Tasks")
                TaskMoreMenu(task: task) { openTask(task.id) }
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.s)
        }
        .frame(width: Theme.Composer.width)
        .onAppear {
            title = task.title
            notes = task.content.isEmpty ? task.desc : task.content
            openedTitle = title
            openedNotes = notes
        }
        // Follow edits made elsewhere while a field isn't being edited.
        .onChange(of: task.title) { _, new in if focus != .title, title == openedTitle { title = new; openedTitle = new } }
        .onChange(of: task.content) { _, new in if focus != .notes, notes == openedNotes { notes = new; openedNotes = new } }
        .onChange(of: focus) { old, _ in if old != nil { commit(task) } }
        .onChange(of: task.priority) { _, _ in priority = nil }
        .onDisappear { commit(task) }
    }

    private func commit(_ task: ZTask) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty, t != openedTitle, t != task.title {
            tasks.core.actions.rename(id: task.id, title: t)
            openedTitle = t
        }
        if notes != openedNotes {
            tasks.core.actions.setContent(id: task.id, content: notes)
            openedNotes = notes
        }
    }

    private func dateText(_ task: ZTask) -> String {
        guard let day = tasks.day(of: task) else { return "Add date" }
        return DateText.short(day: day, task: task)
    }

    private func dateTint(_ task: ZTask) -> AnyShapeStyle {
        guard let day = tasks.day(of: task) else { return AnyShapeStyle(.secondary) }
        if !task.isDone, day < Calendar.current.startOfDay(for: .now) { return AnyShapeStyle(.red) }
        return AnyShapeStyle(.tint)
    }
}

extension EnvironmentValues {
    /// Shows a task in the Tasks area (set by the main window).
    @Entry var openTask: (String) -> Void = { _ in }
    /// Starts a pomo on a task in the Focus area (set by the main window).
    @Entry var startFocus: (String) -> Void = { _ in }
}

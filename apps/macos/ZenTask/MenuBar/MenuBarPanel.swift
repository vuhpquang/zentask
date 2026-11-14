import SwiftUI
@preconcurrency import ZenTaskShared

/// The menu bar window: today's open tasks (tick to complete, click to open) and a field that adds to Today.
struct MenuBarPanel: View {
    @Environment(TasksModel.self) private var tasks
    @State private var text = ""
    @FocusState private var adding: Bool

    private var rows: [TaskRow] {
        guard let index = tasks.index else { return [] }
        let filter = TaskFilterSmart(list: .today)
        return tasks.core.sections(index: index, filter: filter, options: tasks.core.viewOptions(filter: filter))
            .filter { !$0.isCompleted }
            .flatMap(\.rows)
            .filter { !$0.task.isDone }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(alignment: .firstTextBaseline) {
                Text("Today").font(.headline)
                Text(Date.now.formatted(.dateTime.weekday(.wide).day().month())).font(.callout).foregroundStyle(.secondary)
                Spacer()
                Text("\(rows.count)").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
            }
            TextField("Add to Today, e.g. Call Mom 5pm", text: $text)
                .textFieldStyle(.roundedBorder)
                .focused($adding)
                .onSubmit(add)
            if rows.isEmpty {
                Label("Nothing left for today", systemImage: "checkmark.circle")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: Theme.MenuBar.emptyHeight)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(rows.prefix(Theme.MenuBar.maxRows), id: \.task.id) { row in
                            MenuBarRow(row: row)
                        }
                    }
                    .animation(Motion.rows, value: rows.map(\.task.id))
                }
                .frame(maxHeight: Theme.MenuBar.listHeight)
                if rows.count > Theme.MenuBar.maxRows {
                    Text("and \(rows.count - Theme.MenuBar.maxRows) more").font(.caption).foregroundStyle(.secondary)
                }
            }
            Divider()
            HStack {
                Button("Open ZenTask") { showMain() }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
        }
        .padding(Theme.Space.m)
        .frame(width: Theme.MenuBar.width)
        .onAppear { tasks.start() }
    }

    private func add() {
        let title = text.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        _ = tasks.core.quickAdd(text: title, filter: TaskFilterSmart(list: .today), projects: tasks.quickAddProjects, ignored: [])
        text = ""
    }

    private func showMain() {
        NSApp.activate()
        if let window = NSApp.windows.first(where: { $0.identifier?.rawValue.hasPrefix("main") == true && !($0 is NSPanel) }) {
            window.deminiaturize(nil)
            window.makeKeyAndOrderFront(nil)
        } else {
            MainWindowOpener.open?()
        }
    }
}

private struct MenuBarRow: View {
    @Environment(TasksModel.self) private var tasks
    let row: TaskRow

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            CheckButton(task: row.task) { tasks.toggleComplete(row.task) }
            Button {
                tasks.sidebar = .smart(.today)
                tasks.reveal(row.task.id)
                NSApp.activate()
                if let window = NSApp.windows.first(where: { $0.identifier?.rawValue.hasPrefix("main") == true && !($0 is NSPanel) }) {
                    window.makeKeyAndOrderFront(nil)
                } else {
                    MainWindowOpener.open?()
                }
            } label: {
                VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                    Text(row.task.title.isEmpty ? "Untitled" : row.task.title).lineLimit(2)
                    if let day = tasks.day(of: row.task), !row.task.isAllDay || row.overdue {
                        Text(DateText.short(day: day, task: row.task))
                            .font(.caption)
                            .foregroundStyle(row.overdue ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Open in ZenTask")
        }
        .padding(.vertical, Theme.Space.xs)
    }
}

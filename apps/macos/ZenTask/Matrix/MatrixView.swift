import SwiftUI
@preconcurrency import ZenTaskShared

/// The Eisenhower matrix (rail ⌘5): open tasks in four quadrants by priority. Drag a task to another
/// quadrant to change its priority; double-click to open it in Tasks.
struct MatrixView: View {
    @Environment(TasksModel.self) private var tasks
    let openTask: (String) -> Void
    @State private var quadrants: [Quadrant] = []

    private static let looks: [Int32: (title: String, symbol: String, color: Color)] = [
        5: ("Urgent & important", "1.circle.fill", .red),
        3: ("Not urgent & important", "2.circle.fill", .orange),
        1: ("Urgent & not important", "3.circle.fill", .blue),
        0: ("Not urgent & not important", "4.circle.fill", .green),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text("Eisenhower Matrix").font(.title2.weight(.bold))
                .padding(.horizontal, Theme.Space.xl)
                .padding(.top, Theme.Space.l)
            Grid(horizontalSpacing: Theme.Space.l, verticalSpacing: Theme.Space.l) {
                GridRow { quadrant(5); quadrant(3) }
                GridRow { quadrant(1); quadrant(0) }
            }
            .padding(.horizontal, Theme.Space.xl)
            .padding(.bottom, Theme.Space.xl)
        }
        .onAppear { tasks.start() }
        .task(id: tasks.index.map(ObjectIdentifier.init)) {
            guard let index = tasks.index else { return }
            let core = tasks.core
            quadrants = await _Concurrency.Task.detached(priority: .userInitiated) { core.matrix(index: index) }.value
        }
    }

    @ViewBuilder
    private func quadrant(_ priority: Int32) -> some View {
        let look = Self.looks[priority]!
        let q = quadrants.first { $0.priority == priority }
        let count = q?.sections.reduce(0) { $0 + $1.rows.count } ?? 0
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.s) {
                Image(systemName: look.symbol).foregroundStyle(look.color)
                Text(look.title).font(.headline)
                Text("\(count)").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                Spacer()
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(q?.sections ?? [], id: \.key) { section in
                        let folded = tasks.isCollapsed(section, in: "matrix")
                        GroupHeaderRow(title: section.title, count: section.rows.count, collapsed: folded) { tasks.toggleCollapsed(section, in: "matrix") }
                        if !folded {
                            ForEach(section.rows, id: \.task.id) { row in
                                MatrixRow(row: row).onTapGesture(count: 2) { openTask(row.task.id) }
                                Divider()
                            }
                        }
                    }
                }
            }
            .overlay { if count == 0 { Text("Drop tasks here").font(.callout).foregroundStyle(.tertiary) } }
        }
        .padding(Theme.Detail.cardPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).fill(look.color.opacity(Theme.Opacity.matrixFill)))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(look.color.opacity(Theme.Opacity.chip)))
        .dropDestination(for: String.self) { ids, _ in
            let live = ids.filter { tasks.index?.task(id: $0) != nil }
            live.forEach { tasks.core.actions.setPriority(id: $0, priority: priority) }
            return !live.isEmpty
        }
    }
}

private struct MatrixRow: View {
    @Environment(TasksModel.self) private var tasks
    let row: TaskRow

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            CheckButton(task: row.task) { tasks.toggleComplete(row.task) }
            Text(row.task.title.isEmpty ? "Untitled" : row.task.title).lineLimit(1)
            Spacer(minLength: Theme.Space.s)
            if !row.projectName.isEmpty { Text(row.projectName).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            if let day = tasks.day(of: row.task) {
                Text(DateText.short(day: day, task: row.task)).font(.caption)
                    .foregroundStyle(row.overdue ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
            }
        }
        .padding(.vertical, Theme.Space.s)
        .contentShape(Rectangle())
        .draggable(row.task.id)
        .contextMenu { TaskMenu(task: row.task) }
    }
}

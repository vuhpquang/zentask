import SwiftUI
@preconcurrency import ZenTaskShared

/// The add-task field: shows what quick add recognised (tags, priority, list, date) as chips while typing.
struct QuickAddField: View {
    @Environment(TasksModel.self) private var tasks
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    let submit: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            HStack(spacing: Theme.Space.s) {
                Image(systemName: "plus").foregroundStyle(.secondary)
                TextField("Add a task  (#tag  !high  ^list  tomorrow 9am)", text: $text)
                    .textFieldStyle(.plain)
                    .focused(focused)
                    .onSubmit {
                        let value = text.trimmingCharacters(in: .whitespaces)
                        if !value.isEmpty { submit(value) }
                    }
                    .onExitCommand { text = ""; focused.wrappedValue = false }
            }
            .padding(.horizontal, Theme.Space.m)
            .padding(.vertical, Theme.Space.s)
            .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))

            if !chips.isEmpty {
                HStack(spacing: Theme.Space.xs) {
                    ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
                        Label(chip.label, systemImage: chip.symbol)
                            .font(.caption)
                            .padding(.horizontal, Theme.Space.s)
                            .padding(.vertical, Theme.Space.xxs)
                            .background(.tint.opacity(0.15), in: Capsule())
                    }
                }
            }
        }
    }

    private var chips: [(label: String, symbol: String)] {
        guard text.count > 1 else { return [] }
        let q = tasks.parse(text)
        var out: [(String, String)] = []
        if let due = q.due {
            let date = Date(timeIntervalSince1970: Double(due.int64Value) / 1000)
            out.append((q.allDay ? date.formatted(date: .abbreviated, time: .omitted) : date.formatted(date: .abbreviated, time: .shortened), "calendar"))
        }
        if let p = q.priority { out.append((PriorityOption.title(p.int32Value) + " priority", "flag")) }
        if let id = q.projectId { out.append((tasks.projectName(id), "list.bullet")) }
        for tag in q.tags { out.append(("#" + tag, "number")) }
        return out
    }
}

private struct FocusQuickAddKey: FocusedValueKey {
    typealias Value = () -> Void
}

extension FocusedValues {
    var focusQuickAdd: (() -> Void)? {
        get { self[FocusQuickAddKey.self] }
        set { self[FocusQuickAddKey.self] = newValue }
    }
}

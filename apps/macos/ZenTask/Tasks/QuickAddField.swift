import SwiftUI
@preconcurrency import ZenTaskShared

/// The add-task field: shows what quick add recognised (tags, priority, list, date) as chips while typing.
struct QuickAddField: View {
    @Environment(TasksModel.self) private var tasks
    var focused: FocusState<Bool>.Binding
    /// Text and the start offsets of chips the user dismissed.
    let submit: (String, Set<Int>) -> Void

    // Owned here so typing redraws only this field, not the whole task list.
    @State private var text = ""
    @State private var ignored: Set<Int> = []

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            HStack(spacing: Theme.Space.s) {
                Image(systemName: "plus").foregroundStyle(.secondary)
                TextField("Add a task  (#tag  !high  ^list  tomorrow 9am)", text: $text)
                    .textFieldStyle(.plain)
                    .focused(focused)
                    .onSubmit {
                        let value = text.trimmingCharacters(in: .whitespaces)
                        if !value.isEmpty { submit(text, ignored) }
                        text = ""
                        ignored = []
                    }
                    .onExitCommand { text = ""; ignored = []; focused.wrappedValue = false }
                    .onChange(of: text) { _, new in if new.isEmpty { ignored = [] } }
            }
            .padding(.horizontal, Theme.Space.m)
            .padding(.vertical, Theme.Space.s)
            .background(.quaternary.opacity(Theme.Opacity.field), in: RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))

            if !chips.isEmpty {
                HStack(spacing: Theme.Space.xs) {
                    ForEach(chips, id: \.start) { chip in
                        Button { ignored.insert(chip.start) } label: {
                            Label(chip.label, systemImage: chip.symbol)
                        }
                        .buttonStyle(.plain)
                        .font(.caption)
                        .padding(.horizontal, Theme.Space.s)
                        .padding(.vertical, Theme.Space.xxs)
                        .background(.tint.opacity(Theme.Opacity.chip), in: Capsule())
                        .help("Click to keep this as text")
                    }
                }
            }
        }
    }

    private struct Chip {
        let start: Int
        let label: String
        let symbol: String
    }

    /// One chip per recognised token; clicking a chip dismisses that token.
    private var chips: [Chip] {
        guard text.count > 1 else { return [] }
        let q = tasks.parse(text, ignored: ignored)
        return q.tokens.map { token in
            let start = Int(token.start)
            switch token.kind {
            case .tag: return Chip(start: start, label: "#" + token.label, symbol: "number")
            case .priority: return Chip(start: start, label: (q.priority.map { PriorityOption.title($0.int32Value) } ?? token.label) + " priority", symbol: "flag")
            case .list: return Chip(start: start, label: token.label, symbol: "list.bullet")
            default:
                let label: String
                if let due = q.due {
                    let date = Date(timeIntervalSince1970: Double(due.int64Value) / 1000)
                    label = q.allDay ? date.formatted(date: .abbreviated, time: .omitted) : date.formatted(date: .abbreviated, time: .shortened)
                } else {
                    label = token.label
                }
                return Chip(start: start, label: label, symbol: "calendar")
            }
        }
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

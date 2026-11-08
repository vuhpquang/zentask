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
    /// Highlighted suggestion while a `~` / `!` / `#` token is being typed.
    @State private var highlight = 0
    /// Esc closes the suggestions for the token being typed (until the text changes).
    @State private var dismissedToken: String?
    @FocusState private var titleFocused: Bool

    /// A `~list`, `!priority` or `#tag` being typed at the end of the title: its kind, where it starts, the filter text.
    struct Token: Equatable {
        enum Kind { case list, priority, tag }
        let kind: Kind
        let start: String.Index
        let query: String
    }

    struct Suggestion: Identifiable {
        let id: String
        let title: String
        let symbol: String
        let tint: Color
        let pick: () -> Void
    }

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
                    .onChange(of: title) { _, _ in highlight = 0 }
                    .onKeyPress(.downArrow) { move(1) }
                    .onKeyPress(.upArrow) { move(-1) }
                    .onKeyPress(.return) { chooseHighlighted() }
                    .onKeyPress(.tab) { chooseHighlighted() }
                    .onKeyPress(.escape) {
                        guard let t = token else { return .ignored }
                        dismissedToken = String(title[t.start...])
                        return .handled
                    }
                    .overlay(alignment: .topLeading) {
                        if let list = visibleSuggestions, !list.isEmpty {
                            SuggestionList(items: list, highlight: min(highlight, list.count - 1)) { i in choose(list[i]) }
                                .offset(x: Theme.Composer.suggestionIndent, y: Theme.Composer.suggestionOffset)
                        }
                    }
                    .zIndex(1)
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
                    // Return picks a suggestion while the list is open, so Add only owns Return otherwise.
                    .keyboardShortcut(visibleSuggestions == nil ? KeyboardShortcut.defaultAction : KeyboardShortcut(.init("\u{0}"), modifiers: []))
                    .buttonStyle(.borderedProminent)
                    .disabled(parsed.title.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.m)
        }
        .frame(width: Theme.Composer.width)
        .onAppear { titleFocused = true }
    }

    // MARK: Quick pick (~ ! #)

    /// The token being typed: a trigger at the start or after a space, with no space after it yet.
    private var token: Token? { Self.token(in: title) }

    static func token(in title: String) -> Token? {
        guard let i = title.lastIndex(where: { $0 == "~" || $0 == "!" || $0 == "#" }) else { return nil }
        if i > title.startIndex, title[title.index(before: i)] != " " { return nil }
        let query = String(title[title.index(after: i)...])
        guard !query.contains(" ") else { return nil }
        let kind: Token.Kind = title[i] == "~" ? .list : title[i] == "!" ? .priority : .tag
        return Token(kind: kind, start: i, query: query)
    }

    /// The title without the token (and the space before it), ending in one space if anything is left.
    static func removing(_ t: Token, from title: String) -> String {
        let rest = String(title[..<t.start]).replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
        return rest.isEmpty ? "" : rest + " "
    }

    private var visibleSuggestions: [Suggestion]? {
        guard let t = token, dismissedToken != String(title[t.start...]) else { return nil }
        return suggestions(for: t)
    }

    private func suggestions(for t: Token) -> [Suggestion] {
        let q = SearchOverlay.fold(t.query)
        let matches = { (name: String) in q.isEmpty || SearchOverlay.fold(name).contains(q) }
        switch t.kind {
        case .list:
            return listChoices.filter { matches($0.1) }.map { id, name in
                Suggestion(id: "l:" + id, title: name, symbol: "list.bullet", tint: Color(hex: tasks.projects.first { $0.id == id }?.color) ?? .accentColor) { listId = id }
            }
        case .priority:
            return PriorityOption.all.filter { matches($0.title) }.map { p in
                Suggestion(id: "p:\(p.value)", title: p.title, symbol: "flag.fill", tint: Theme.PriorityColor.color(p.value)) { priority = p.value }
            }
        case .tag:
            var out = tasks.tags.filter { matches($0.name) || matches($0.label) }.map { t in
                Suggestion(id: "t:" + t.name, title: "#" + (t.label.isEmpty ? t.name : t.label), symbol: "number", tint: Color(hex: t.color) ?? .accentColor) {
                    if !tags.contains(t.name) { tags.append(t.name) }
                }
            }
            let typed = t.query.trimmingCharacters(in: .whitespaces)
            if !typed.isEmpty, !tasks.tags.contains(where: { SearchOverlay.fold($0.name) == SearchOverlay.fold(typed) }) {
                out.append(Suggestion(id: "new:" + typed, title: "Create #\(typed)", symbol: "plus", tint: .accentColor) {
                    if !tags.contains(typed) { tags.append(typed) }
                })
            }
            return out
        }
    }

    private func move(_ by: Int) -> KeyPress.Result {
        guard let list = visibleSuggestions, !list.isEmpty else { return .ignored }
        highlight = (min(highlight, list.count - 1) + by + list.count) % list.count
        return .handled
    }

    private func chooseHighlighted() -> KeyPress.Result {
        guard let list = visibleSuggestions, !list.isEmpty else { return .ignored }
        choose(list[min(highlight, list.count - 1)])
        return .handled
    }

    /// Applies the pick and removes the `~query` text from the title.
    private func choose(_ s: Suggestion) {
        s.pick()
        if let t = token { title = Self.removing(t, from: title) }
        highlight = 0
        titleFocused = true
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

/// The floating suggestion list under the title (the text field keeps focus; ↑↓ and Return drive it).
private struct SuggestionList: View {
    let items: [TaskComposer.Suggestion]
    let highlight: Int
    let pick: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.prefix(Theme.Composer.maxSuggestions).enumerated()), id: \.element.id) { i, item in
                Button { pick(i) } label: {
                    HStack(spacing: Theme.Space.s) {
                        Image(systemName: item.symbol).foregroundStyle(item.tint).frame(width: Theme.List.pickerIcon)
                        Text(item.title).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, Theme.Space.s)
                    .frame(height: Theme.List.pickerRow)
                    .background(RoundedRectangle(cornerRadius: Theme.Radius.tile - Theme.Space.xs, style: .continuous)
                        .fill(i == highlight ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear)))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(Theme.Space.xs)
        .frame(width: Theme.Composer.suggestionWidth)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous).strokeBorder(.quaternary))
        .shadow(radius: Theme.Space.s, y: Theme.Space.xxs)
    }
}

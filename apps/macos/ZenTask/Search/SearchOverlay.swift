import SwiftUI
@preconcurrency import ZenTaskShared

/// ⌘K / rail search: one field over tasks (title and notes), lists, smart lists and tags. Accent marks and
/// "đ" are ignored, so "dong ho" finds "đồng hồ". ↑/↓ move, Return opens, Esc closes.
struct SearchOverlay: View {
    @Environment(TasksModel.self) private var tasks
    let open: (SidebarItem, String?) -> Void
    let close: () -> Void
    @State private var query = ""
    @State private var cursor = 0
    @FocusState private var focused: Bool
    /// Tasks with their accent-free title and notes, built once when the overlay opens.
    @State private var corpus: [(task: ZTask, title: String, notes: String)] = []

    struct Hit: Identifiable {
        let id: String
        let title: String
        let detail: String
        let symbol: String
        let item: SidebarItem
        let taskId: String?
    }

    var body: some View {
        let hits = results
        VStack(spacing: 0) {
            HStack(spacing: Theme.Space.m) {
                Image(systemName: "magnifyingglass").font(.title3).foregroundStyle(.secondary)
                TextField("Search tasks, lists, smart lists and tags", text: $query)
                    .textFieldStyle(.plain)
                    .font(.title3)
                    .focused($focused)
                    .onSubmit { if hits.indices.contains(cursor) { go(hits[cursor]) } }
                    .onChange(of: query) { _, _ in cursor = 0 }
                Button(action: close) { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.plain)
                    .keyboardShortcut(.cancelAction)
                    .help("Close")
            }
            .padding(Theme.Space.l)
            Divider()
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                ContentUnavailableView("Search everything", systemImage: "magnifyingglass", description: Text("Tasks, notes, lists, smart lists and tags"))
                    .frame(height: Theme.Search.emptyHeight)
            } else if hits.isEmpty {
                ContentUnavailableView.search(text: query).frame(height: Theme.Search.emptyHeight)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(hits.enumerated()), id: \.element.id) { i, hit in
                                Button { go(hit) } label: {
                                    HStack(spacing: Theme.Space.m) {
                                        Image(systemName: hit.symbol).frame(width: Theme.List.pickerIcon).foregroundStyle(.tint)
                                        VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                                            Text(hit.title).lineLimit(1)
                                            if !hit.detail.isEmpty { Text(hit.detail).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                        }
                                        Spacer()
                                    }
                                    .padding(.horizontal, Theme.Space.l)
                                    .padding(.vertical, Theme.Space.s)
                                    .background(i == cursor ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear))
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .id(i)
                            }
                        }
                    }
                    .frame(maxHeight: Theme.Search.resultsHeight)
                    .onChange(of: cursor) { _, c in proxy.scrollTo(c) }
                }
            }
        }
        .frame(width: Theme.Search.width)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(.quaternary))
        .shadow(radius: Theme.Space.xl, y: Theme.Space.s)
        .onAppear {
            focused = true
            corpus = (tasks.index?.allTasks ?? []).filter { $0.trashedAt == nil && !$0.deleted }.map { ($0, Self.fold($0.title), Self.fold($0.content)) }
        }
        .onKeyPress(.downArrow) { cursor = min(cursor + 1, max(0, hits.count - 1)); return .handled }
        .onKeyPress(.upArrow) { cursor = max(cursor - 1, 0); return .handled }
    }

    private func go(_ hit: Hit) {
        open(hit.item, hit.taskId)
        close()
    }

    static func fold(_ s: String) -> String {
        s.replacingOccurrences(of: "đ", with: "d").replacingOccurrences(of: "Đ", with: "d")
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    private var results: [Hit] {
        let q = Self.fold(query.trimmingCharacters(in: .whitespaces))
        guard !q.isEmpty else { return [] }
        var out: [Hit] = []
        for p in tasks.listProjects where Self.fold(p.name).contains(q) {
            out.append(Hit(id: "p:" + p.id, title: p.name, detail: "List", symbol: "list.bullet", item: .project(p.id), taskId: nil))
        }
        for f in tasks.filters where Self.fold(f.name).contains(q) {
            out.append(Hit(id: "f:" + f.id, title: f.name, detail: "Smart list", symbol: "line.3.horizontal.decrease.circle", item: .custom(f.id), taskId: nil))
        }
        for t in tasks.tags where Self.fold(t.name).contains(q) || Self.fold(t.label).contains(q) {
            out.append(Hit(id: "t:" + t.id, title: "#" + (t.label.isEmpty ? t.name : t.label), detail: "Tag", symbol: "number", item: .tag(t.name), taskId: nil))
        }
        var found = 0
        for (t, title, notes) in corpus where found < Theme.Search.maxTasks {
            let inTitle = title.contains(q)
            guard inTitle || notes.contains(q) else { continue }
            found += 1
            let where_ = tasks.projectName(t.projectId) + (t.isDone ? " · completed" : "")
            out.append(Hit(id: "k:" + t.id, title: t.title.isEmpty ? "Untitled" : t.title, detail: inTitle ? where_ : where_ + " · in notes",
                           symbol: t.isDone ? "checkmark.square" : "square", item: t.isDone ? .smart(.completed) : .project(t.projectId), taskId: t.id))
        }
        return out
    }
}

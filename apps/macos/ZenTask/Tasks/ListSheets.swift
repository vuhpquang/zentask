import SwiftUI
@preconcurrency import ZenTaskShared

/// Colours offered for lists and tags (ZenTask's own palette; stored as #RRGGBB).
enum Palette {
    static let colors = ["#5B5FE9", "#3B82F6", "#8B5CF6", "#EC4899", "#EF4444", "#F59E0B", "#2E9E88", "#64748B"]
}

/// Routes a ListSheet to its view.
struct ListSheetView: View {
    @Environment(TasksModel.self) private var tasks
    let sheet: ListSheet

    var body: some View {
        switch sheet {
        case .newList: ListEditor(project: nil)
        case .editList(let id): ListEditor(project: tasks.projects.first { $0.id == id })
        case .newFolder: NameSheet(title: "New folder", initial: "", action: "Create") { _ = tasks.core.listActions.createFolder(name: $0) }
        case .renameFolder(let id):
            NameSheet(title: "Rename folder", initial: tasks.groups.first { $0.id == id }?.name ?? "", action: "Rename") {
                tasks.core.listActions.renameFolder(id: id, name: $0)
            }
        case .newSection(let projectId):
            NameSheet(title: "New section", initial: "", action: "Add") { _ = tasks.core.listActions.createColumn(projectId: projectId, name: $0) }
        case .renameSection(let id, let name):
            NameSheet(title: "Rename section", initial: name, action: "Rename") { tasks.core.listActions.renameColumn(id: id, name: $0) }
        case .newFilter: FilterEditor(filter: nil)
        case .editFilter(let id): FilterEditor(filter: tasks.filters.first { $0.id == id })
        }
    }
}

/// A one-field sheet: Cancel on the left, the primary action on the right (↵), Esc cancels.
struct NameSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let initial: String
    let action: String
    let save: (String) -> Void
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(title).font(.title3.weight(.semibold))
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
            HStack {
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(action) { save(name.trimmingCharacters(in: .whitespaces)); dismiss() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.Size.sheetWidth)
        .onAppear { name = initial }
    }
}

/// Create or edit a list: name, colour, folder.
struct ListEditor: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    let project: Project?
    @State private var name = ""
    @State private var color: String? = Palette.colors[0]
    @State private var folder: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(project == nil ? "New list" : "Edit list").font(.title3.weight(.semibold))
            TextField("Name", text: $name).textFieldStyle(.roundedBorder).controlSize(.large)
            HStack(spacing: Theme.Space.s) {
                ForEach(Palette.colors, id: \.self) { hex in
                    Button { color = hex } label: {
                        Circle()
                            .fill(Color(hex: hex) ?? .secondary)
                            .frame(width: Theme.Size.swatch, height: Theme.Size.swatch)
                            .overlay(Circle().strokeBorder(.primary, lineWidth: color == hex ? 2 : 0))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Colour \(hex)")
                }
            }
            Picker("Folder", selection: $folder) {
                Text("None").tag(String?.none)
                ForEach(tasks.groups, id: \.id) { g in Text(g.name).tag(String?.some(g.id)) }
            }
            HStack {
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(project == nil ? "Create" : "Save") { save(); dismiss() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.Size.sheetWidth)
        .onAppear {
            name = project?.name ?? ""
            color = project == nil ? Palette.colors[0] : project?.color
            folder = project?.groupId
        }
    }

    private func save() {
        let actions = tasks.core.listActions
        let clean = name.trimmingCharacters(in: .whitespaces)
        if let project {
            if clean != project.name { try? actions.rename(id: project.id, name: clean) }
            if color != project.color { actions.setColor(id: project.id, color: color) }
            if folder != project.groupId { actions.move(id: project.id, groupId: folder) }
        } else {
            let created = actions.createList(name: clean, color: color, groupId: folder)
            tasks.sidebar = .project(created.id)
        }
    }
}

/// Create or edit a custom smart list.
struct FilterEditor: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    let filter: Filter?
    @State private var name = ""
    @State private var lists: Set<String> = []
    @State private var tags: Set<String> = []
    @State private var priorities: Set<Int32> = []
    @State private var due = "any"
    @State private var keyword = ""

    @State private var matchAny = false
    @State private var excludeLists = false
    /// "any", "all" or "exclude".
    @State private var tagLogic = "any"
    @State private var icon = ""
    private let dues = [
        ("any", "Any date"), ("today", "Today"), ("tomorrow", "Tomorrow"), ("thisweek", "This week"), ("nextweek", "Next week"),
        ("next7", "Next 7 days"), ("thismonth", "This month"), ("overdue", "Overdue"), ("nodate", "No date"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(filter == nil ? "New smart list" : "Edit smart list").font(.title3.weight(.semibold))
            Form {
                HStack {
                    TextField("Icon", text: $icon)
                        .frame(width: Theme.Size.iconField)
                        .help("An emoji shown in the sidebar")
                    TextField("Name", text: $name)
                }
                Picker("Show tasks matching", selection: $matchAny) {
                    Text("All conditions").tag(false)
                    Text("Any condition").tag(true)
                }
                .pickerStyle(.segmented)
                Section {
                    ForEach(tasks.projects.filter { !$0.closed }, id: \.id) { p in
                        Toggle(p.name, isOn: binding($lists, p.id))
                    }
                } header: {
                    HStack {
                        Text("Lists")
                        Spacer()
                        Picker("", selection: $excludeLists) {
                            Text("Include").tag(false)
                            Text("Exclude").tag(true)
                        }
                        .pickerStyle(.segmented)
                        .fixedSize()
                    }
                }
                if !tasks.tags.isEmpty {
                    Section {
                        ForEach(tasks.tags, id: \.id) { t in Toggle("#" + t.name, isOn: binding($tags, t.name)) }
                    } header: {
                        HStack {
                            Text("Tags")
                            Spacer()
                            Picker("", selection: $tagLogic) {
                                Text("Any").tag("any")
                                Text("All").tag("all")
                                Text("Exclude").tag("exclude")
                            }
                            .pickerStyle(.segmented)
                            .fixedSize()
                        }
                    }
                }
                Section("Priority") {
                    HStack {
                        ForEach(PriorityOption.all, id: \.value) { p in Toggle(p.title, isOn: binding($priorities, p.value)).toggleStyle(.button) }
                    }
                }
                Picker("Date", selection: $due) {
                    ForEach(dues, id: \.0) { Text($0.1).tag($0.0) }
                }
                TextField("Contains text", text: $keyword)
            }
            .formStyle(.grouped)
            .frame(height: Theme.Size.filterFormHeight)
            HStack {
                if filter != nil {
                    Button("Delete…", role: .destructive) {
                        tasks.confirmDeleteFilter = filter
                        dismiss()
                    }
                }
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(filter == nil ? "Create" : "Save") { save(); dismiss() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.Size.filterWidth)
        .onAppear(perform: load)
    }

    private func binding<T: Hashable>(_ set: Binding<Set<T>>, _ value: T) -> Binding<Bool> {
        Binding(get: { set.wrappedValue.contains(value) }, set: { on in if on { set.wrappedValue.insert(value) } else { set.wrappedValue.remove(value) } })
    }

    private func load() {
        guard let filter else { return }
        let rule = tasks.core.filterRule(filter: filter)
        name = filter.name
        icon = filter.icon ?? ""
        excludeLists = rule.lists.isEmpty && !rule.excludeLists.isEmpty
        lists = Set(excludeLists ? rule.excludeLists : rule.lists)
        tagLogic = !rule.excludeTags.isEmpty && rule.tags.isEmpty ? "exclude" : (rule.tagsAll ? "all" : "any")
        tags = Set(tagLogic == "exclude" ? rule.excludeTags : rule.tags)
        priorities = Set(rule.priorities.map { $0.int32Value })
        due = rule.due ?? "any"
        keyword = rule.keyword ?? ""
        matchAny = rule.match == "any"
    }

    private func save() {
        let rule = FilterRule(
            lists: excludeLists ? [] : Array(lists), tags: tagLogic == "exclude" ? [] : Array(tags),
            priorities: priorities.map { KotlinInt(int: $0) },
            due: due == "any" ? nil : due, keyword: keyword.isEmpty ? nil : keyword, match: matchAny ? "any" : "all",
            excludeLists: excludeLists ? Array(lists) : [], excludeTags: tagLogic == "exclude" ? Array(tags) : [],
            tagsAll: tagLogic == "all"
        )
        let saved = tasks.core.saveFilter(id: filter?.id, name: name, rule: rule, icon: icon.isEmpty ? nil : String(icon.prefix(2)))
        tasks.sidebar = .custom(saved.id)
    }
}

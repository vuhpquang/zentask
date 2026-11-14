import SwiftUI
@preconcurrency import ZenTaskShared

struct SidebarView: View {
    @Environment(\.themeColors) private var themeColors
    @Environment(TasksModel.self) private var tasks
    @Environment(AppModel.self) private var app

    private let smart: [SmartList] = [.today, .tomorrow, .next7Days, .inbox, .all]
    private let status: [SmartList] = [.completed, .wontDo, .trash]

    var body: some View {
        @Bindable var tasks = tasks
        // No List selection: the system draws it in the AccentColor asset, so a theme's accent never showed and the
        // themed icons vanished on it. Rows draw their own highlight (`select`).
        List {
            Section {
                ForEach(smart.filter { tasks.shows($0) }, id: \.self) { list in
                    row(TasksModel.title(list), symbol: TasksModel.symbol(list), item: .smart(list))
                }
                Label { Text("Summary") } icon: { icon("doc.text.magnifyingglass") }
                    .modifier(SidebarSelect(selected: tasks.showingSummary, count: 0) { tasks.showingSummary = true })
                Label { Text("Statistics") } icon: { icon("chart.bar.xaxis") }
                    .modifier(SidebarSelect(selected: tasks.showingStats, count: 0) { tasks.showingStats = true })
            }
            Section {
                ForEach(ungroupedProjects, id: \.id) { project in
                    projectRow(project)
                }
                ForEach(tasks.groups, id: \.id) { group in
                    DisclosureGroup {
                        ForEach(tasks.listProjects.filter { $0.groupId == group.id }, id: \.id) { projectRow($0) }
                    } label: {
                        Label { Text(group.name) } icon: { icon("folder") }
                            .contextMenu {
                                Button("Rename…") { tasks.sheet = .renameFolder(group.id) }
                                Button("Delete folder") { tasks.core.listActions.deleteFolder(id: group.id) }
                            }
                    }
                }
            } header: {
                SectionHeader(title: "Lists") {
                    Button("New list…") { tasks.sheet = .newList }
                    Button("New folder…") { tasks.sheet = .newFolder }
                }
            }
            Section {
                ForEach(tasks.filters, id: \.id) { filter in
                    Label {
                        Text(filter.name)
                    } icon: {
                        if let emoji = filter.icon, !emoji.isEmpty { Text(emoji) } else { icon("line.3.horizontal.decrease.circle") }
                    }
                        .modifier(select(.custom(filter.id)))
                        .contextMenu {
                            Button("Edit…") { tasks.sheet = .editFilter(filter.id) }
                            Button("Delete…", role: .destructive) { tasks.confirmDeleteFilter = filter }
                        }
                }
            } header: {
                SectionHeader(title: "Smart lists") {
                    Button("New smart list…") { tasks.sheet = .newFilter }
                }
            }
            if !tasks.tags.isEmpty {
                Section("Tags") {
                    ForEach(tasks.tags, id: \.id) { tag in
                        Label {
                            Text(tag.label.isEmpty ? tag.name : tag.label)
                        } icon: {
                            Image(systemName: "number").foregroundStyle(Color(hex: tag.color) ?? .secondary)
                        }
                        .modifier(select(.tag(tag.name)))
                        .dropDestination(for: String.self) { ids, _ in tasks.drop(ids, onto: .tag(tag.name)) }
                    }
                }
            }
            if !tasks.archivedProjects.isEmpty {
                Section {
                    DisclosureGroup {
                        ForEach(tasks.archivedProjects, id: \.id) { projectRow($0) }
                    } label: {
                        Label { Text("Archived") } icon: { icon("archivebox") }
                    }
                }
            }
            Section {
                ForEach(status.filter { tasks.shows($0) }, id: \.self) { list in
                    Label { Text(TasksModel.title(list)) } icon: { icon(TasksModel.symbol(list)) }
                        .modifier(select(.smart(list), counted: false))
                        .dropDestination(for: String.self) { ids, _ in tasks.drop(ids, onto: .smart(list)) }
                }
            }
        }
        .listStyle(.sidebar)
        .themedListBackground(themeColors, surface: true)
        .environment(\.sidebarRowSize, .medium)
        .safeAreaInset(edge: .bottom) { SyncFooter() }
    }

    private var ungroupedProjects: [Project] {
        let groupIds = Set(tasks.groups.map(\.id))
        return tasks.listProjects.filter { $0.groupId == nil || !groupIds.contains($0.groupId!) }
    }

    /// A sidebar-style List tints icons with the app's AccentColor asset, not `.tint`, so themed icons set it here.
    private func icon(_ symbol: String) -> some View {
        Image(systemName: symbol).foregroundStyle(themeColors.accent)
    }

    private func select(_ item: SidebarItem, counted: Bool = true) -> SidebarSelect {
        SidebarSelect(selected: !tasks.showingSummary && !tasks.showingStats && tasks.sidebar == item, count: counted ? tasks.count(item) : 0) { tasks.sidebar = item }
    }

    private func row(_ title: String, symbol: String, item: SidebarItem) -> some View {
        Label { Text(title) } icon: { icon(symbol) }
            .modifier(select(item))
            .dropDestination(for: String.self) { ids, _ in tasks.drop(ids, onto: item) }
    }

    private func projectRow(_ project: Project) -> some View {
        Label {
            Text(project.name)
        } icon: {
            Image(systemName: "list.bullet").foregroundStyle(Color(hex: project.color) ?? .secondary)
        }
        .modifier(select(.project(project.id)))
        .contextMenu { ListMenu(project: project) }
        .dropDestination(for: String.self) { ids, _ in tasks.drop(ids, onto: .project(project.id)) }
    }
}

/// A clickable sidebar row with the theme's selection highlight (a tinted rounded rectangle, like the rail's) and its
/// count. Drawn inside the row: a sidebar-style List ignores `listRowBackground`, and its own selection uses the
/// AccentColor asset, never the theme.
struct SidebarSelect: ViewModifier {
    @Environment(\.themeColors) private var themeColors
    let selected: Bool
    let count: Int
    let action: () -> Void

    func body(content: Content) -> some View {
        HStack(spacing: Theme.Space.xs) {
            content.layoutPriority(1)
            Spacer(minLength: 0)
            if count > 0 {
                Text("\(count)").font(.subheadline).monospacedDigit().foregroundStyle(.secondary).fixedSize()
            }
        }
        .fontWeight(selected ? .semibold : .regular)
        .padding(.vertical, Theme.Sidebar.selectionInsetV)
        .padding(.horizontal, Theme.Sidebar.selectionInsetH)
        .background(
            RoundedRectangle(cornerRadius: Theme.Radius.sidebarRow, style: .continuous)
                .fill(selected ? AnyShapeStyle(themeColors.accent.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear))
        )
        .padding(.vertical, -Theme.Sidebar.selectionInsetV)
        .padding(.horizontal, -Theme.Sidebar.selectionInsetH)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// A sidebar section title with a "+" menu.
private struct SectionHeader<Items: View>: View {
    let title: String
    @ViewBuilder let items: () -> Items

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Menu { items() } label: { Image(systemName: "plus") }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Add")
        }
    }
}

/// Right-click menu of a list.
struct ListMenu: View {
    @Environment(TasksModel.self) private var tasks
    let project: Project

    var body: some View {
        let actions = tasks.core.listActions
        Button("Edit…") { tasks.sheet = .editList(project.id) }
        Button("Add section…") { tasks.sheet = .newSection(project.id) }
        if !tasks.groups.isEmpty {
            Menu("Move to folder") {
                Button("None") { actions.move(id: project.id, groupId: nil) }
                ForEach(tasks.groups, id: \.id) { g in Button(g.name) { actions.move(id: project.id, groupId: g.id) } }
            }
        }
        Divider()
        if project.closed {
            Button("Unarchive") { actions.unarchive(id: project.id) }
        } else {
            Button("Archive") { try? actions.archive(id: project.id) }
        }
        Button("Move to Trash", role: .destructive) {
            try? actions.trash(id: project.id)
            if tasks.sidebar == .project(project.id) { tasks.sidebar = .smart(.today) }
        }
    }
}

/// Sync state and a manual sync button at the bottom of the sidebar.
private struct SyncFooter: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            if app.syncFailed {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            Text(app.signedIn ? app.syncText : "Not signed in")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 0)
            if app.signedIn {
                Button { app.syncNow() } label: { Image(systemName: "arrow.triangle.2.circlepath") }
                    .buttonStyle(.borderless)
                    .help("Sync now")
            } else {
                SettingsLink { Text("Sign in…") }.buttonStyle(.borderless).font(.caption)
            }
        }
        .padding(.horizontal, Theme.Space.m)
        .padding(.vertical, Theme.Space.s)
    }
}

extension Color {
    /// `#RRGGBB` (TickTick-style list/tag colours); nil when absent or malformed.
    init?(hex: String?) {
        guard var s = hex?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

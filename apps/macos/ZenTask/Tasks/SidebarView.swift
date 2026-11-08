import SwiftUI
@preconcurrency import ZenTaskShared

struct SidebarView: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(AppModel.self) private var app

    private let smart: [SmartList] = [.today, .tomorrow, .next7Days, .inbox, .all]
    private let status: [SmartList] = [.completed, .wontDo, .trash]

    var body: some View {
        @Bindable var tasks = tasks
        List(selection: Binding(get: { tasks.sidebar }, set: { if let v = $0 { tasks.sidebar = v } })) {
            Section {
                ForEach(smart, id: \.self) { list in
                    row(TasksModel.title(list), symbol: TasksModel.symbol(list), item: .smart(list))
                }
            }
            if !tasks.listProjects.isEmpty {
                Section("Lists") {
                    ForEach(ungroupedProjects, id: \.id) { project in
                        projectRow(project)
                    }
                    ForEach(tasks.groups.filter { g in tasks.listProjects.contains { $0.groupId == g.id } }, id: \.id) { group in
                        DisclosureGroup {
                            ForEach(tasks.listProjects.filter { $0.groupId == group.id }, id: \.id) { projectRow($0) }
                        } label: {
                            Label(group.name, systemImage: "folder")
                        }
                    }
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
                        .badge(tasks.count(.tag(tag.name)))
                        .tag(SidebarItem.tag(tag.name))
                    }
                }
            }
            Section {
                ForEach(status, id: \.self) { list in
                    Label(TasksModel.title(list), systemImage: TasksModel.symbol(list)).tag(SidebarItem.smart(list))
                }
            }
        }
        .listStyle(.sidebar)
        .environment(\.sidebarRowSize, .large)
        .safeAreaInset(edge: .bottom) { SyncFooter() }
    }

    private var ungroupedProjects: [Project] {
        let groupIds = Set(tasks.groups.map(\.id))
        return tasks.listProjects.filter { $0.groupId == nil || !groupIds.contains($0.groupId!) }
    }

    private func row(_ title: String, symbol: String, item: SidebarItem) -> some View {
        Label(title, systemImage: symbol)
            .font(.body)
            .badge(tasks.count(item))
            .tag(item)
    }

    private func projectRow(_ project: Project) -> some View {
        Label {
            Text(project.name)
        } icon: {
            Image(systemName: "list.bullet").foregroundStyle(Color(hex: project.color) ?? .secondary)
        }
        .badge(tasks.count(.project(project.id)))
        .tag(SidebarItem.project(project.id))
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

import SwiftUI
@preconcurrency import ZenTaskShared

/// Manage task templates: what each one makes, rename, delete, or make a task from it now.
struct TemplatesSheet: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    @State private var renaming: TaskTemplate?
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text("Templates").font(.title3.weight(.semibold))
            if tasks.templates.isEmpty {
                ContentUnavailableView("No templates", systemImage: "doc.on.doc",
                                       description: Text("Right-click a task and choose Save as template. Its notes, checklist and subtasks come along."))
                    .frame(maxHeight: .infinity)
            } else {
                List {
                    ForEach(tasks.templates, id: \.id) { tpl in
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                                Text(tpl.name.isEmpty ? tpl.title : tpl.name).font(.headline)
                                Text(summary(tpl)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                            }
                            Spacer()
                            Button("Use") { tasks.use(template: tpl); dismiss() }
                            Menu {
                                Button("Rename…") { newName = tpl.name; renaming = tpl }
                                Button("Delete", role: .destructive) { tasks.core.templateActions.delete(id: tpl.id) }
                            } label: { Image(systemName: "ellipsis") }
                            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                        }
                        .padding(.vertical, Theme.Space.xxs)
                    }
                }
                .listStyle(.inset)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.PlanDay.width, height: Theme.PlanDay.height)
        .alert("Rename template", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Rename") { if let r = renaming { tasks.core.templateActions.rename(id: r.id, name: newName) }; renaming = nil }
        }
    }

    private func summary(_ t: TaskTemplate) -> String {
        var parts = [t.title]
        if !t.checklist.isEmpty { parts.append("\(t.checklist.count) checklist item\(t.checklist.count == 1 ? "" : "s")") }
        if !t.subtasks.isEmpty { parts.append("\(t.subtasks.count) subtask\(t.subtasks.count == 1 ? "" : "s")") }
        if !t.tags.isEmpty { parts.append(t.tags.map { "#" + $0 }.joined(separator: " ")) }
        return parts.joined(separator: " · ")
    }
}

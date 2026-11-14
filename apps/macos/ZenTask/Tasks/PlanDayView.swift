import SwiftUI
@preconcurrency import ZenTaskShared

/// "Plan my day": overdue, coming-up and important undated tasks to pick for today (their times are kept).
struct PlanDayView: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    @State private var sections: [TaskSection] = []
    @State private var picked: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                Label("Plan my day", systemImage: "sun.horizon").font(.title3.weight(.semibold))
                Text("Pick what you'll do today. Tasks keep their time.").foregroundStyle(.secondary)
            }
            if sections.isEmpty {
                ContentUnavailableView("Nothing to plan", systemImage: "checkmark.circle", description: Text("No overdue, upcoming or important tasks"))
                    .frame(maxHeight: .infinity)
            } else {
                List {
                    ForEach(sections, id: \.key) { section in
                        Section {
                            ForEach(section.rows, id: \.task.id) { row in
                                Toggle(isOn: Binding(get: { picked.contains(row.task.id) }, set: { on in
                                    if on { picked.insert(row.task.id) } else { picked.remove(row.task.id) }
                                })) {
                                    VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                                        Text(row.task.title.isEmpty ? "Untitled" : row.task.title).lineLimit(2)
                                        HStack(spacing: Theme.Space.s) {
                                            if let day = tasks.day(of: row.task) {
                                                Label(DateText.short(day: day, task: row.task), systemImage: "calendar")
                                                    .foregroundStyle(row.overdue ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                                            }
                                            if !row.projectName.isEmpty { Text(row.projectName).foregroundStyle(.secondary) }
                                        }
                                        .font(.caption)
                                        .labelStyle(.titleAndIcon)
                                    }
                                }
                                .toggleStyle(.checkbox)
                            }
                        } header: {
                            HStack {
                                Text(section.title)
                                Spacer()
                                Button(section.rows.allSatisfy { picked.contains($0.task.id) } ? "None" : "All") {
                                    let ids = section.rows.map(\.task.id)
                                    if ids.allSatisfy(picked.contains) { picked.subtract(ids) } else { picked.formUnion(ids) }
                                }
                                .buttonStyle(.link)
                            }
                        }
                    }
                }
                .listStyle(.inset)
            }
            HStack {
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(picked.isEmpty ? "Add to today" : "Add \(picked.count) to today") {
                    tasks.core.planToday(ids: Array(picked))
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(picked.isEmpty)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.PlanDay.width, height: Theme.PlanDay.height)
        .onAppear {
            if let index = tasks.index { sections = tasks.core.dayPlan(index: index) }
        }
    }
}

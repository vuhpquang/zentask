import SwiftUI

/// The Tasks screen: sidebar | task list | detail.
struct TasksView: View {
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        @Bindable var tasks = tasks
        NavigationSplitView(columnVisibility: $tasks.columnVisibility) {
            SidebarView()
                .navigationSplitViewColumnWidth(min: Theme.Size.sidebarMin, ideal: 230)
        } content: {
            TaskListView()
                .navigationSplitViewColumnWidth(min: Theme.Size.listMin, ideal: 440)
        } detail: {
            HStack(spacing: 0) {
                Group {
                    if let task = tasks.selectedTask {
                        TaskDetailView(task: task)
                            .id(task.id)
                    } else {
                        ContentUnavailableView("No task selected", systemImage: "checkmark.circle", description: Text("Select a task to see its details"))
                    }
                }
                .frame(maxWidth: .infinity)
                if tasks.showCalendarPanel {
                    Divider()
                    CalendarPanel()
                        .frame(width: Theme.Panel.calendarWidth)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    withAnimation(Theme.List.foldAnimation) { tasks.showCalendarPanel.toggle() }
                } label: {
                    Label("Calendar", systemImage: "sidebar.right")
                }
                .help(tasks.showCalendarPanel ? "Hide the calendar" : "Show the calendar beside your tasks")
            }
        }
        .onAppear { tasks.start() }
        .sheet(item: Binding(get: { tasks.sheet }, set: { tasks.sheet = $0 })) { ListSheetView(sheet: $0).environment(tasks) }
        .confirmationDialog(
            "Delete the list \"\(tasks.confirmDeleteList?.name ?? "")\" permanently?",
            isPresented: Binding(get: { tasks.confirmDeleteList != nil }, set: { if !$0 { tasks.confirmDeleteList = nil } })
        ) {
            Button("Delete permanently", role: .destructive) {
                if let p = tasks.confirmDeleteList { try? tasks.core.listActions.deleteForever(id: p.id) }
                tasks.confirmDeleteList = nil
            }
            Button("Cancel", role: .cancel) { tasks.confirmDeleteList = nil }
        } message: {
            Text("The list, its sections and the tasks trashed with it are deleted on every device. This can't be undone.")
        }
        .confirmationDialog(
            "Delete the smart list \"\(tasks.confirmDeleteFilter?.name ?? "")\"?",
            isPresented: Binding(get: { tasks.confirmDeleteFilter != nil }, set: { if !$0 { tasks.confirmDeleteFilter = nil } })
        ) {
            Button("Delete", role: .destructive) {
                if let f = tasks.confirmDeleteFilter {
                    tasks.core.deleteFilter(id: f.id)
                    if tasks.sidebar == .custom(f.id) { tasks.sidebar = .smart(.today) }
                }
                tasks.confirmDeleteFilter = nil
            }
            Button("Cancel", role: .cancel) { tasks.confirmDeleteFilter = nil }
        } message: {
            Text("Only the smart list is deleted; its tasks stay where they are.")
        }
    }
}

import SwiftUI

/// The Tasks screen: sidebar | task list | detail.
struct TasksView: View {
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(min: Theme.Size.sidebarMin, ideal: 230)
        } content: {
            TaskListView()
                .navigationSplitViewColumnWidth(min: Theme.Size.listMin, ideal: 440)
        } detail: {
            if let task = tasks.selectedTask {
                TaskDetailView(task: task)
                    .id(task.id)
            } else {
                ContentUnavailableView("No task selected", systemImage: "checkmark.circle", description: Text("Select a task to see its details"))
            }
        }
        .onAppear { tasks.start() }
    }
}

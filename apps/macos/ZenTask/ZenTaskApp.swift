import SwiftUI

@main
struct ZenTaskApp: App {
    @State private var model: AppModel
    @State private var tasks: TasksModel

    init() {
        if let dir = ProcessInfo.processInfo.environment["ZENTASK_SNAPSHOT"] {
            Snapshots.render(to: URL(fileURLWithPath: dir))
            exit(0)
        }
        let model = AppModel.live()
        _model = State(initialValue: model)
        let tasks = TasksModel(core: model.core)
        _tasks = State(initialValue: tasks)
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            tasks.reminders = Reminders(core: model.core)
            model.start()
        }
    }

    var body: some Scene {
        WindowGroup {
            MainWindow()
                .environment(model)
                .environment(tasks)
        }
        .defaultSize(width: 1100, height: 720)
        .commands { TaskCommands() }

        Settings {
            SettingsView()
                .environment(model)
        }
    }
}

/// Menu bar: Task menu with keyboard shortcuts.
struct TaskCommands: Commands {
    @FocusedValue(\.focusQuickAdd) private var focusQuickAdd

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Task") { focusQuickAdd?() }
                .keyboardShortcut("n")
                .disabled(focusQuickAdd == nil)
        }
    }
}

/// Rail + the selected area.
struct MainWindow: View {
    @State private var tab: RailTab = .tasks

    var body: some View {
        HStack(spacing: 0) {
            AppRail(tab: $tab)
            Divider()
            TasksView()
        }
        .ignoresSafeArea(.container, edges: .top)
    }
}

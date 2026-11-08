import SwiftUI

@main
struct ZenTaskApp: App {
    @State private var model: AppModel
    @State private var tasks: TasksModel
    @State private var calendar: CalendarModel
    @State private var habits: HabitsModel
    @State private var countdowns: CountdownsModel
    @State private var panelCalendar: PanelCalendar

    init() {
        if let dir = ProcessInfo.processInfo.environment["ZENTASK_SNAPSHOT"] {
            Snapshots.render(to: URL(fileURLWithPath: dir))
            exit(0)
        }
        let model = AppModel.live()
        _model = State(initialValue: model)
        let tasks = TasksModel(core: model.core)
        _tasks = State(initialValue: tasks)
        _calendar = State(initialValue: CalendarModel(core: model.core))
        _habits = State(initialValue: HabitsModel(core: model.core))
        _countdowns = State(initialValue: CountdownsModel(core: model.core))
        _panelCalendar = State(initialValue: PanelCalendar(core: model.core))
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
                .environment(calendar)
                .environment(habits)
                .environment(countdowns)
                .environment(panelCalendar)
                .onOpenURL { tasks.open($0) }
        }
        .defaultSize(width: 1100, height: 720)
        .commands { TaskCommands() }

        Settings {
            SettingsView()
                .environment(model)
                .environment(calendar)
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
                .ignoresSafeArea(.container, edges: .top) // only the rail reaches under the traffic lights
            Divider().ignoresSafeArea(.container, edges: .top)
            switch tab {
            case .calendar: CalendarView()
            case .habits: HabitsView()
            case .countdown: CountdownsView()
            default: TasksView()
            }
        }
    }
}

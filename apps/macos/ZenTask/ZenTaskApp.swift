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
    @Environment(TasksModel.self) private var tasks
    @State private var tab: RailTab = .tasks
    @State private var searching = false

    var body: some View {
        HStack(spacing: 0) {
            AppRail(tab: $tab, search: { withAnimation(Theme.List.foldAnimation) { searching = true } })
                .ignoresSafeArea(.container, edges: .top) // only the rail reaches under the traffic lights
            Divider().ignoresSafeArea(.container, edges: .top)
            switch tab {
            case .calendar: CalendarView()
            case .habits: HabitsView()
            case .countdown: CountdownsView()
            case .matrix: MatrixView(openTask: { id in open(nil, id) })
            default: TasksView()
            }
        }
        .overlay(alignment: .top) {
            if searching {
                ZStack(alignment: .top) {
                    Color.black.opacity(Theme.Opacity.scrim).ignoresSafeArea()
                        .onTapGesture { withAnimation(Theme.List.foldAnimation) { searching = false } }
                    SearchOverlay(open: { item, id in open(item, id) }, close: { withAnimation(Theme.List.foldAnimation) { searching = false } })
                        .padding(.top, Theme.Search.topInset)
                }
                .transition(.opacity)
            }
        }
    }

    /// Shows a list (and a task in it) in the Tasks area.
    private func open(_ item: SidebarItem?, _ taskId: String?) {
        tab = .tasks
        if let item { tasks.sidebar = item } else if let id = taskId, let t = tasks.index?.task(id: id) {
            tasks.sidebar = t.isDone ? .smart(.completed) : .project(t.projectId)
        }
        if let taskId { tasks.selection = taskId }
    }
}

import AboutKit
import AppShellCore
import SwiftUI
import UpdaterKit

@main
struct ZenTaskApp: App {
    @State private var model: AppModel
    @State private var tasks: TasksModel
    @State private var calendar: CalendarModel
    @State private var habits: HabitsModel
    @State private var countdowns: CountdownsModel
    @State private var panelCalendar: PanelCalendar
    @State private var focus: FocusModel

    init() {
        if let dir = ProcessInfo.processInfo.environment["ZENTASK_SNAPSHOT"] {
            Snapshots.render(to: URL(fileURLWithPath: dir))
            exit(0)
        }
        #if DEBUG
        if ProcessInfo.processInfo.environment["ZENTASK_UPDATE_DRYRUN"] != nil {
            let done = DispatchSemaphore(value: 0)
            Task { @MainActor in print(await Updater.shared.dryRun()); done.signal() }
            while done.wait(timeout: .now()) == .timedOut { RunLoop.main.run(until: Date().addingTimeInterval(0.1)) }
            exit(0)
        }
        #endif
        let model = AppModel.live()
        _model = State(initialValue: model)
        let tasks = TasksModel(core: model.core)
        _tasks = State(initialValue: tasks)
        _calendar = State(initialValue: CalendarModel(core: model.core))
        _habits = State(initialValue: HabitsModel(core: model.core))
        _countdowns = State(initialValue: CountdownsModel(core: model.core))
        _panelCalendar = State(initialValue: PanelCalendar(core: model.core))
        _focus = State(initialValue: FocusModel(core: model.core))
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            tasks.reminders = Reminders(core: model.core)
            model.start()
            IntentBridge.tasks = tasks
            IntentBridge.focus = _focus.wrappedValue
            Updater.shared.startAutomaticChecks()
            WhatsNewTracker.showAfterUpdate()
            _focus.wrappedValue.start() // a running pomo keeps ticking and is recorded even before Focus is opened
            HotKeys.shared.start(handlers: [
                .quickAdd: { tasks.start(); QuickAddWindow.shared.show(tasks: tasks) },
                .toggleWindow: { toggleMainWindow() },
            ])
            MenuBarController.shared.start(tasks: tasks)
        }
    }

    var body: some Scene {
        WindowGroup(id: "main") {
            MainWindow()
                .environment(model)
                .environment(tasks)
                .environment(calendar)
                .environment(habits)
                .environment(countdowns)
                .environment(panelCalendar)
                .environment(focus)
                .modifier(Themed())
        }
        .defaultSize(width: 1100, height: 720)
        // No title bar: the traffic lights sit over the rail, like the window's own chrome (every area).
        .windowStyle(.hiddenTitleBar)
        .commands { TaskCommands() }

        Settings {
            SettingsView()
                .environment(model)
                .environment(tasks)
                .environment(calendar)
                .modifier(Themed())
        }
    }
}

/// Menu bar: Task menu with keyboard shortcuts.
struct TaskCommands: Commands {
    @FocusedValue(\.focusQuickAdd) private var focusQuickAdd

    var body: some Commands {
        CommandGroup(replacing: .appInfo) {
            Button("About ZenTask") { AboutWindow.show(app: .zenTask, updater: .shared) }
            CheckForUpdatesButton()
            Button("What's New") {
                WhatsNewWindow.show(app: .zenTask, version: AppIdentity.zenTask.version, tracker: .shared)
            }
        }
        CommandGroup(replacing: .newItem) {
            Button("New Task") { focusQuickAdd?() }
                .keyboardShortcut("n")
                .disabled(focusQuickAdd == nil)
        }
    }
}

/// App menu → Check for Updates…: opens Settings → Updates and checks.
private struct CheckForUpdatesButton: View {
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Button("Check for Updates…") {
            UserDefaults.standard.set(SettingsPane.updates.rawValue, forKey: SettingsPane.storageKey)
            openSettings()
            Updater.shared.check(userInitiated: true)
        }
    }
}

/// Rail + the selected area.
struct MainWindow: View {
    @Environment(\.openWindow) private var openWindow
    @Environment(TasksModel.self) private var tasks
    @Environment(FocusModel.self) private var focusModel
    @State private var tab: RailTab = {
        #if DEBUG
        if ProcessInfo.processInfo.environment["ZENTASK_OPEN_AREA"] == "calendar" { return .calendar }
        #endif
        return .tasks
    }()
    @State private var searching = false

    var body: some View {
        HStack(spacing: 0) {
            AppRail(tab: $tab, search: { withMotion { searching = true } })
                .ignoresSafeArea(.container, edges: .top) // only the rail reaches under the traffic lights
            Divider().ignoresSafeArea(.container, edges: .top)
            Group {
                switch tab {
                case .calendar: CalendarView()
                case .habits: HabitsView()
                case .countdown: CountdownsView()
                case .matrix: MatrixView(openTask: { id in open(nil, id) })
                case .focus: FocusView()
                default: TasksView()
                }
            }
            .animation(Motion.fade, value: tab)
        }
        .environment(\.searchOpen, searching)
        .onAppear {
            IntentBridge.openArea = { tab = $0 }
            let open = openWindow
            MainWindowOpener.open = { open(id: "main") }
            IntentBridge.openSmart = { tasks.sidebar = .smart($0) }
        }
        .environment(\.openTask) { id in open(nil, id) }
        .environment(\.startFocus) { id in
            tab = .focus
            if focusModel.phase == .idle { focusModel.mode = .pomo; focusModel.begin(task: id) } else { focusModel.taskId = id }
        }
        .onOpenURL { url in
            // zentask://task/<id> (Copy link): show it in Tasks whatever area is open.
            guard url.scheme == "zentask", url.host() == "task", let id = url.pathComponents.dropFirst().first else { return }
            open(nil, id)
        }
        .overlay(alignment: .top) {
            if searching {
                ZStack(alignment: .top) {
                    Color.black.opacity(Theme.Opacity.scrim).ignoresSafeArea()
                        .onTapGesture { withMotion { searching = false } }
                    SearchOverlay(open: { item, id in open(item, id) }, close: { withMotion { searching = false } })
                        .padding(.top, Theme.Search.topInset)
                }
                .transition(.opacity)
            }
        }
    }

    /// Shows a list (and a task in it) in the Tasks area.
    private func open(_ item: SidebarItem?, _ taskId: String?) {
        tab = .tasks
        if let taskId {
            tasks.reveal(taskId)
        } else if let item {
            tasks.sidebar = item
        }
    }
}

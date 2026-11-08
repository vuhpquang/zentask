import AppKit
import SwiftUI
@preconcurrency import ZenTaskShared

/// `ZENTASK_SNAPSHOT=<dir> ZenTask.app/Contents/MacOS/ZenTask` renders every screen in light and dark
/// to PNGs (offscreen, in-memory database, no Keychain) and exits. Used by the design check.
@MainActor
enum Snapshots {
    static func render(to dir: URL) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let model = AppModel(core: ZenTaskCore.companion.open(dbPath: nil),
                             keychain: Keychain(service: "com.vuhpquang.zentask.snapshot"))
        let tasks = TasksModel(core: model.core)
        let panel = PanelCalendar(core: model.core)
        let settings: [(String, AnyView, CGSize)] = [
            ("settings-account", AnyView(AccountSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 300)),
            ("settings-ticktick", AnyView(TickTickSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 520)),
            ("settings-smartlists", AnyView(SmartListSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 480)),
        ]
        for (name, view, size) in settings { both(view.environment(model), size: size, name: name, dir: dir) }
        wait { await tasks.reload() }
        both(AnyView(MainWindow()).environment(model).environment(tasks).environment(panel), size: windowSize, name: "tasks-empty", dir: dir)

        calendarShots(model, tasks, dir: dir)
        habitShots(model, tasks, dir: dir)
        if let csv = sampleCsv() {
            let core = model.core
            wait { _ = try? await core.importCsv(text: csv) }
            wait { await tasks.reload() }
            let shots: [(String, SidebarItem)] = [("tasks-inbox", .smart(.inbox)), ("tasks-all", .smart(.all)), ("tasks-completed", .smart(.completed))]
            for (name, item) in shots {
                tasks.sidebar = item
                wait { await tasks.settle() }
                both(AnyView(MainWindow()).environment(model).environment(tasks).environment(panel), size: windowSize, name: name, dir: dir)
            }
            if let project = tasks.listProjects.first(where: { $0.viewMode == "kanban" }) ?? tasks.listProjects.first {
                tasks.sidebar = .project(project.id)
                wait { await tasks.settle() }
                both(AnyView(MainWindow()).environment(model).environment(tasks).environment(panel), size: windowSize, name: "tasks-list", dir: dir)
            }
            tasks.sheet = nil
            for (name, view) in [("sheet-list", AnyView(ListEditor(project: nil))), ("sheet-filter", AnyView(FilterEditor(filter: nil)))] {
                both(view.environment(model).environment(tasks), size: CGSize(width: 480, height: 620), name: name, dir: dir)
            }
            both(AnyView(MatrixView(openTask: { _ in })).environment(model).environment(tasks), size: windowSize, name: "matrix", dir: dir)
            both(AnyView(TaskComposer(start: .allDay(.now)) {}).environment(tasks), size: CGSize(width: 440, height: 300), name: "composer", dir: dir)
            if let sample = tasks.index?.allTasks.first(where: { !$0.content.isEmpty && $0.isOpen }) {
                both(AnyView(TaskQuickEditor(taskId: sample.id)).environment(tasks), size: CGSize(width: 440, height: 320), name: "calendar-task-card", dir: dir)
            }
            both(AnyView(HotkeySettings().frame(width: Theme.Size.settingsWidth)), size: CGSize(width: 540, height: 560), name: "settings-hotkeys", dir: dir)
            both(AnyView(SearchOverlay(open: { _, _ in }, close: {})).environment(tasks), size: CGSize(width: 600, height: 340), name: "search", dir: dir)
            tasks.sidebar = .smart(.all)
            wait { await tasks.settle() }
            if let checklist = tasks.sections.flatMap(\.rows).first(where: { $0.checklistTotal > 0 }) ?? tasks.sections.first?.rows.first {
                tasks.selection = checklist.task.id
                both(AnyView(MainWindow()).environment(model).environment(tasks).environment(panel), size: windowSize, name: "tasks-detail", dir: dir)
                // List grouped by list with one group folded, and the calendar panel open.
                let cal = CalendarModel(core: model.core)
                tasks.setOptions(tasks.options.with(sort: "priority", group: "list"))
                wait { await tasks.settle() }
                if let second = tasks.sections.dropFirst().first { tasks.toggleCollapsed(second, in: tasks.sidebar.storageKey) }
                tasks.showCalendarPanel = true
                wait { await panel.cal.reload() }
                both(AnyView(MainWindow()).environment(model).environment(tasks).environment(panel).environment(cal), size: CGSize(width: 1500, height: 820), name: "tasks-panel", dir: dir)
                tasks.showCalendarPanel = false
                if let second = tasks.sections.dropFirst().first { tasks.toggleCollapsed(second, in: tasks.sidebar.storageKey) }
                tasks.setOptions(tasks.options.with(sort: "", group: ""))
            }
        }
    }

    /// Week and month with a few sample events (offscreen, in-memory core only).
    private static func calendarShots(_ model: AppModel, _ tasks: TasksModel, dir: URL) {
        let core = model.core
        let cal = CalendarModel(core: core)
        let day = Calendar.current.startOfDay(for: .now)
        SampleData.shared.addCalendar(core: core, dayStart: Int64(day.timeIntervalSince1970 * 1000))
        wait { await tasks.reload(); await cal.reload() }
        for mode in [CalendarModel.Mode.week, .month, .agenda] {
            cal.mode = mode
            wait { await cal.reload() }
            both(AnyView(CalendarView()).environment(model).environment(tasks).environment(cal).environment(PanelCalendar(core: core)), size: windowSize, name: "calendar-\(mode.rawValue.lowercased())", dir: dir)
        }
        both(AnyView(CalendarSettings().frame(width: Theme.Size.settingsWidth)).environment(model).environment(cal), size: CGSize(width: 540, height: 420), name: "settings-calendars", dir: dir)
        cal.mode = .week
        wait { await cal.reload() }
        UserDefaults.standard.set(true, forKey: "calendar.showSideBar")
        both(AnyView(CalendarView()).environment(model).environment(tasks).environment(cal).environment(PanelCalendar(core: core)), size: CGSize(width: 1400, height: 760), name: "calendar-sidebar", dir: dir)
        UserDefaults.standard.removeObject(forKey: "calendar.showSideBar")
        both(AnyView(CalendarViewOptionsSheet()).environment(cal), size: CGSize(width: 460, height: 420), name: "sheet-calendar-options", dir: dir)
    }

    /// Habits and countdowns with sample data (in-memory core only).
    private static func habitShots(_ model: AppModel, _ tasks: TasksModel, dir: URL) {
        let core = model.core
        let habits = HabitsModel(core: core)
        let countdowns = CountdownsModel(core: core)
        let read = core.habitActions.create(name: "Read 20 pages", icon: "📚", color: "#5B5FE9", type: "BOOLEAN", goal: 1, step: 1, unit: "", repeatRule: "RRULE:FREQ=DAILY;INTERVAL=1")
        let water = core.habitActions.create(name: "Drink water", icon: nil, color: "#3B82F6", type: "REAL", goal: 8, step: 1, unit: "cups", repeatRule: "RRULE:FREQ=DAILY;INTERVAL=1")
        _ = core.habitActions.create(name: "Gym", icon: nil, color: "#EC4899", type: "BOOLEAN", goal: 1, step: 1, unit: "", repeatRule: "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")
        _ = core.habitActions.create(name: "Meditate", icon: nil, color: "#8B5CF6", type: "BOOLEAN", goal: 1, step: 1, unit: "", repeatRule: "RRULE:FREQ=WEEKLY;TT_TIMES=3")
        let today = core.todayStamp()
        for back in [0, 1, 2, 3, 5, 6, 8, 9, 10, 11, 12] {
            core.habitCheckIn(habitId: read.id, stamp: habits.stamp(Calendar.current.date(byAdding: .day, value: -back, to: .now)!))
        }
        core.habitAdd(habitId: water.id, stamp: today, amount: 3)
        let year = Calendar.current.component(.year, from: .now)
        _ = core.countdownActions.create(name: "Trip to Đà Lạt", date: habits.stamp(Calendar.current.date(byAdding: .day, value: 12, to: .now)!), type: "COUNTDOWN", repeat: "none", color: "#2E9E88", icon: "🏔️", note: "", showInCalendar: true)
        _ = core.countdownActions.create(name: "Mom's birthday", date: Int32(19601110), type: "BIRTHDAY", repeat: "yearly", color: "#EC4899", icon: nil, note: "", showInCalendar: true)
        _ = core.countdownActions.create(name: "Wedding anniversary", date: Int32(20160320), type: "ANNIVERSARY", repeat: "yearly", color: "#EF4444", icon: nil, note: "", showInCalendar: true)
        _ = core.countdownActions.create(name: "New year", date: Int32((year + 1) * 10_000 + 101), type: "HOLIDAY", repeat: "none", color: "#F59E0B", icon: "🎆", note: "", showInCalendar: false)
        _ = core.countdownActions.create(name: "Started ZenTask", date: Int32(20261108), type: "COUNTDOWN", repeat: "none", color: "#5B5FE9", icon: nil, note: "", showInCalendar: false)
        wait { await habits.reload(); await countdowns.reload() }
        habits.selection = read.id
        let env = { (v: AnyView) in AnyView(v.environment(model).environment(tasks).environment(habits).environment(countdowns)) }
        both(env(AnyView(HabitsView())), size: windowSize, name: "habits", dir: dir)
        habits.selection = water.id
        both(env(AnyView(HabitsView())), size: windowSize, name: "habits-number", dir: dir)
        both(env(AnyView(CountdownsView())), size: windowSize, name: "countdowns", dir: dir)
        both(env(AnyView(HabitEditor(editing: .new))), size: CGSize(width: 480, height: 560), name: "sheet-habit", dir: dir)
        both(env(AnyView(CountdownEditor(editing: .new))), size: CGSize(width: 500, height: 520), name: "sheet-countdown", dir: dir)
    }

    private static let windowSize = CGSize(width: 1100, height: 720)

    private static func both(_ view: some View, size: CGSize, name: String, dir: URL) {
        for (suffix, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            write(view, size: size, appearance: appearance, to: dir.appendingPathComponent("\(name)-\(suffix).png"))
        }
    }

    /// Runs async work to completion while keeping the main run loop turning.
    private static func wait(_ work: @escaping @MainActor () async -> Void) {
        var done = false
        _Concurrency.Task { @MainActor in await work(); done = true }
        while !done { RunLoop.current.run(until: Date().addingTimeInterval(0.02)) }
    }

    private static func sampleCsv() -> String? {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/TickTick-backup-2026-11-08.csv")
        return try? String(contentsOf: url, encoding: .utf8)
    }

    private static func write(_ view: some View, size: CGSize, appearance: NSAppearance.Name, to url: URL) {
        let host = NSHostingView(rootView: view.frame(width: size.width, height: size.height).background(Color(nsColor: .windowBackgroundColor)))
        host.appearance = NSAppearance(named: appearance)
        host.frame = CGRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: appearance)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
        host.cacheDisplay(in: host.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
    }
}

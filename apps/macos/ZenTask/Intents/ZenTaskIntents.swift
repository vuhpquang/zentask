import AppIntents
import AppKit
@preconcurrency import ZenTaskShared

/// What the Shortcuts actions need from the running app (set at launch).
@MainActor
enum IntentBridge {
    static var tasks: TasksModel?
    static var focus: FocusModel?
    /// Opens an area of the main window (set by MainWindow).
    static var openArea: ((RailTab) -> Void)?
    static var openSmart: ((SmartList) -> Void)?

    static func core() throws -> ZenTaskCore {
        guard let core = tasks?.core else { throw IntentError.notReady }
        return core
    }
}

enum IntentError: Error, CustomLocalizedStringResourceConvertible {
    case notReady, empty
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .notReady: "ZenTask isn't ready yet. Open it and try again."
        case .empty: "There's no text to add."
        }
    }
}

/// Adds a task (quick-add syntax works: "Call bank tomorrow 9am #finance !high").
struct AddTaskIntent: AppIntent {
    static let title: LocalizedStringResource = "Add a task"
    static let description = IntentDescription("Adds a task to ZenTask. Dates, #tags, !priority and ~list in the text are recognised.")
    static let openAppWhenRun = false

    @Parameter(title: "Task") var text: String
    @Parameter(title: "For today", default: false) var today: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let added = try IntentBridge.add(lines: [text], today: today)
        return .result(dialog: "Added \"\(added.first ?? text)\"")
    }
}

/// Adds the clipboard's text: one task, or one per line.
struct AddClipboardIntent: AppIntent {
    static let title: LocalizedStringResource = "Add tasks from the clipboard"
    static let description = IntentDescription("Adds the clipboard's text to ZenTask as one task, or one task per line.")
    static let openAppWhenRun = false

    @Parameter(title: "One task per line", default: true) var perLine: Bool
    @Parameter(title: "For today", default: false) var today: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let text = NSPasteboard.general.string(forType: .string) ?? ""
        let lines = perLine ? text.split(whereSeparator: \.isNewline).map(String.init) : [text]
        let added = try IntentBridge.add(lines: lines, today: today)
        return .result(dialog: added.count == 1 ? "Added \"\(added[0])\"" : "Added \(added.count) tasks")
    }
}

struct StartFocusIntent: AppIntent {
    static let title: LocalizedStringResource = "Start a pomo"
    static let description = IntentDescription("Starts a Pomodoro in ZenTask's Focus.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let focus = IntentBridge.focus else { throw IntentError.notReady }
        IntentBridge.openArea?(.focus)
        if focus.phase == .idle { focus.mode = .pomo; focus.begin() }
        return .result()
    }
}

struct OpenTodayIntent: AppIntent {
    static let title: LocalizedStringResource = "Show Today"
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentBridge.openArea?(.tasks)
        IntentBridge.openSmart?(.today)
        return .result()
    }
}

struct OpenCalendarIntent: AppIntent {
    static let title: LocalizedStringResource = "Show Calendar"
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentBridge.openArea?(.calendar)
        return .result()
    }
}

extension IntentBridge {
    /// Adds each non-empty line with quick-add parsing; "today" dates undated ones today. Returns the titles.
    static func add(lines: [String], today: Bool) throws -> [String] {
        let core = try core()
        guard let tasks else { throw IntentError.notReady }
        let clean = lines.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard !clean.isEmpty else { throw IntentError.empty }
        let midnight = KotlinLong(value: Int64(Calendar.current.startOfDay(for: .now).timeIntervalSince1970 * 1000))
        return clean.compactMap { line in
            let q = tasks.parse(line)
            guard !q.title.isEmpty else { return nil }
            let due = q.due ?? (today ? midnight : nil)
            _ = core.actions.create(title: q.title, filter: nil, projectId: q.projectId, priority: q.priority, tags: q.tags,
                                    due: due, allDay: q.due != nil ? q.allDay : true)
            return q.title
        }
    }
}

/// Phrases for Siri and Spotlight, and the actions listed in the Shortcuts app.
struct ZenTaskShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: AddClipboardIntent(), phrases: ["Add the clipboard to \(.applicationName)"], shortTitle: "Add from clipboard", systemImageName: "doc.on.clipboard")
        AppShortcut(intent: StartFocusIntent(), phrases: ["Start a pomo in \(.applicationName)"], shortTitle: "Start pomo", systemImageName: "timer")
        AppShortcut(intent: OpenTodayIntent(), phrases: ["Show today in \(.applicationName)"], shortTitle: "Today", systemImageName: "sun.max")
        AppShortcut(intent: OpenCalendarIntent(), phrases: ["Show my \(.applicationName) calendar"], shortTitle: "Calendar", systemImageName: "calendar")
    }
}

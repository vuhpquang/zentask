import Foundation
import WidgetKit
@preconcurrency import ZenTaskShared

/// Writes `widget.json` for the desktop widgets after the data changes, and asks WidgetKit to redraw them
/// (only when the content changed).
@MainActor
enum WidgetFeed {
    private static var last: WidgetSnapshot?
    private static let enabled: Bool = {
        let env = ProcessInfo.processInfo.environment
        return env["XCTestConfigurationFilePath"] == nil && env["ZENTASK_SNAPSHOT"] == nil
    }()

    static func update(core: ZenTaskCore, index: TaskIndex) {
        guard enabled else { return }
        let snapshot = make(core: core, index: index)
        guard snapshot != last else { return }
        last = snapshot
        let dev = Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true
        let folder = WidgetSnapshot.folder(dev: dev)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try JSONEncoder().encode(snapshot).write(to: folder.appendingPathComponent(WidgetSnapshot.fileName), options: .atomic)
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            last = nil
        }
    }

    static func make(core: ZenTaskCore, index: TaskIndex) -> WidgetSnapshot {
        let filter = TaskFilterSmart(list: .today)
        let rows = core.sections(index: index, filter: filter, options: core.viewOptions(filter: filter))
            .filter { !$0.isCompleted }.flatMap(\.rows).filter { !$0.task.isDone }
        let today = rows.prefix(30).map { r -> WidgetSnapshot.TaskItem in
            let t = r.task
            var detail = ""
            if let ms = t.startDate ?? t.dueDate {
                let day = Date(timeIntervalSince1970: Double(ms.int64Value) / 1000)
                if !Calendar.current.isDateInToday(day) { detail = DateText.short(day: day, task: t) }
                else if !t.isAllDay { detail = day.formatted(date: .omitted, time: .shortened) }
            }
            return .init(id: t.id, title: t.title.isEmpty ? "Untitled" : t.title, detail: detail, overdue: r.overdue, priority: Int(t.priority))
        }
        let countdowns = core.countdownCards().filter { $0.countdown.archivedAt == nil && $0.daysLeft >= 0 }
            .sorted { $0.daysLeft < $1.daysLeft }.prefix(6).map { c in
                WidgetSnapshot.CountdownItem(
                    name: c.countdown.name.isEmpty ? "Countdown" : c.countdown.name, daysLeft: Int(c.daysLeft),
                    date: Date(timeIntervalSince1970: Double(c.dateMillis) / 1000).formatted(.dateTime.weekday(.abbreviated).day().month()))
            }
        return WidgetSnapshot(today: Array(today), countdowns: Array(countdowns))
    }
}

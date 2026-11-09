import AppKit
import Foundation
import UserNotifications
@preconcurrency import ZenTaskShared

/// Schedules ZenTask's local notifications (task and habit reminders, countdowns, the morning summary;
/// planned in shared `NotificationPlanner`) and handles their actions (Complete, Check in, Snooze…).
/// Planned requests have ids starting `zt.` so rescheduling replaces exactly those; snoozes use `zs.` and
/// survive reschedules.
@MainActor
final class Reminders: NSObject, UNUserNotificationCenterDelegate {
    private enum Category {
        static let task = "zentask.task"
        static let habit = "zentask.habit"
        static let plain = "zentask.plain"
    }
    private static let complete = "complete"
    private static let checkIn = "checkin"
    private static let snoozePrefix = "snooze."

    private let center = UNUserNotificationCenter.current()
    private let core: ZenTaskCore

    init(core: ZenTaskCore) {
        self.core = core
        super.init()
        center.delegate = self
        let snoozes = Snooze.shared.ALL.map { UNNotificationAction(identifier: Self.snoozePrefix + $0, title: Snooze.shared.title(option: $0)) }
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Category.task, actions: [UNNotificationAction(identifier: Self.complete, title: "Complete")] + snoozes, intentIdentifiers: []),
            UNNotificationCategory(identifier: Category.habit, actions: [UNNotificationAction(identifier: Self.checkIn, title: "Check in")] + snoozes, intentIdentifiers: []),
            UNNotificationCategory(identifier: Category.plain, actions: [], intentIdentifiers: []),
        ])
    }

    /// Replaces our pending notifications with [planned]. Asks for permission the first time there is one to show.
    func reschedule(_ planned: [PlannedReminder]) async {
        let ours = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("zt.") }
        center.removePendingNotificationRequests(withIdentifiers: ours)
        guard !planned.isEmpty else { return }
        guard await authorized(ask: true) else { return }
        let sound = core.notificationPrefs().sound
        for reminder in planned {
            let date = Date(timeIntervalSince1970: Double(reminder.fireAt) / 1000)
            try? await center.add(request(id: reminder.id, content: content(for: reminder, at: date, sound: sound), at: date))
        }
    }

    /// The system's notification permission for ZenTask.
    func permission() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    /// Whether notifications may be shown; asks once when [ask] and the user hasn't decided yet.
    func authorized(ask: Bool) async -> Bool {
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined && ask {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
            status = await center.notificationSettings().authorizationStatus
        }
        return status == .authorized || status == .provisional
    }

    private func content(for r: PlannedReminder, at date: Date, sound: Bool) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        switch r.kind {
        case PlannedReminder.companion.KIND_TASK:
            content.title = r.title.isEmpty ? "Task" : r.title
            content.body = date.formatted(date: .omitted, time: .shortened)
            content.categoryIdentifier = Category.task
        case PlannedReminder.companion.KIND_HABIT:
            content.title = r.title
            content.body = r.body
            content.categoryIdentifier = Category.habit
        default:
            content.title = r.title
            content.body = r.body
            content.categoryIdentifier = Category.plain
        }
        content.sound = sound ? .default : nil
        content.userInfo = [
            "kind": r.kind, "taskId": r.taskId, "habitId": r.habitId, "stamp": Int(r.stamp),
            "title": content.title, "body": content.body, "category": content.categoryIdentifier,
        ]
        return content
    }

    private func request(id: String, content: UNNotificationContent, at date: Date) -> UNNotificationRequest {
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        let requestId = response.notification.request.identifier
        let payload = Payload(info)
        await MainActor.run { handle(action: action, requestId: requestId, payload: payload) }
    }

    /// The parts of a notification's userInfo the actions need (Sendable copy).
    private struct Payload: Sendable {
        let kind: String
        let taskId: String
        let habitId: String
        let stamp: Int32
        let title: String
        let body: String
        let category: String

        init(_ info: [AnyHashable: Any]) {
            kind = info["kind"] as? String ?? PlannedReminder.companion.KIND_TASK
            taskId = info["taskId"] as? String ?? ""
            habitId = info["habitId"] as? String ?? ""
            stamp = Int32(info["stamp"] as? Int ?? 0)
            title = info["title"] as? String ?? ""
            body = info["body"] as? String ?? ""
            category = info["category"] as? String ?? Category.task
        }
    }

    private func handle(action: String, requestId: String, payload: Payload) {
        switch action {
        case Self.complete:
            if !payload.taskId.isEmpty { core.actions.complete(id: payload.taskId) }
        case Self.checkIn:
            if !payload.habitId.isEmpty { core.habitMarkDone(habitId: payload.habitId, stamp: payload.stamp) }
        case let a where a.hasPrefix(Self.snoozePrefix):
            snooze(payload, option: String(a.dropFirst(Self.snoozePrefix.count)), requestId: requestId)
        case UNNotificationDefaultActionIdentifier:
            open(payload)
        default:
            break
        }
    }

    /// Shows the same notification again later, under a `zs.` id that reschedules leave alone.
    private func snooze(_ p: Payload, option: String, requestId: String) {
        let date = Date(timeIntervalSince1970: Double(core.snoozeUntil(option: option)) / 1000)
        let content = UNMutableNotificationContent()
        content.title = p.title
        content.body = p.body
        content.categoryIdentifier = p.category
        content.sound = core.notificationPrefs().sound ? .default : nil
        content.userInfo = ["kind": p.kind, "taskId": p.taskId, "habitId": p.habitId, "stamp": Int(p.stamp), "title": p.title, "body": p.body, "category": p.category]
        let base = requestId.hasPrefix("zs.") ? String(requestId.dropFirst(3)) : requestId
        center.add(request(id: "zs.\(base)", content: content, at: date))
    }

    private func open(_ p: Payload) {
        if p.kind == PlannedReminder.companion.KIND_TASK, !p.taskId.isEmpty, let url = URL(string: "zentask://task/\(p.taskId)") {
            NSWorkspace.shared.open(url)
        } else {
            NSApp.activate()
        }
    }

    /// A test notification (Settings → Notifications), in five seconds.
    func sendTest() async -> Bool {
        guard await authorized(ask: true) else { return false }
        let content = UNMutableNotificationContent()
        content.title = "ZenTask"
        content.body = "Notifications are on"
        content.sound = core.notificationPrefs().sound ? .default : nil
        content.categoryIdentifier = Category.plain
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)
        try? await center.add(UNNotificationRequest(identifier: "zs.test", content: content, trigger: trigger))
        return true
    }
}

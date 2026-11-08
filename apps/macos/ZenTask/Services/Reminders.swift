import Foundation
import UserNotifications
@preconcurrency import ZenTaskShared

/// Schedules task reminders as local notifications and handles their actions (Complete, Snooze).
/// Our requests are prefixed `zt.` so rescheduling replaces exactly ours.
@MainActor
final class Reminders: NSObject, UNUserNotificationCenterDelegate {
    private static let category = "zentask.task"
    private static let complete = "complete"
    private static let snooze = "snooze"

    private let center = UNUserNotificationCenter.current()
    private let core: ZenTaskCore

    init(core: ZenTaskCore) {
        self.core = core
        super.init()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.category,
                actions: [
                    UNNotificationAction(identifier: Self.complete, title: "Complete"),
                    UNNotificationAction(identifier: Self.snooze, title: "Snooze 15 min"),
                ],
                intentIdentifiers: []
            ),
        ])
    }

    /// Replaces our pending notifications with [planned]. Asks for permission the first time there is one to show.
    func reschedule(_ planned: [PlannedReminder]) async {
        let ours = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("zt.") }
        center.removePendingNotificationRequests(withIdentifiers: ours)
        guard !planned.isEmpty else { return }
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
            status = await center.notificationSettings().authorizationStatus
        }
        guard status == .authorized || status == .provisional else { return }
        for reminder in planned {
            let date = Date(timeIntervalSince1970: Double(reminder.fireAt) / 1000)
            try? await center.add(request(id: reminder.id, taskId: reminder.taskId, title: reminder.title, at: date))
        }
    }

    private func request(id: String, taskId: String, title: String, at date: Date) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = title.isEmpty ? "Task" : title
        content.body = date.formatted(date: .omitted, time: .shortened)
        content.sound = .default
        content.categoryIdentifier = Self.category
        content.userInfo = ["taskId": taskId, "title": title]
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let taskId = info["taskId"] as? String else { return }
        let title = info["title"] as? String ?? ""
        let action = response.actionIdentifier
        await MainActor.run {
            switch action {
            case Self.complete:
                core.actions.complete(id: taskId)
            case Self.snooze:
                let later = Date().addingTimeInterval(15 * 60)
                self.center.add(request(id: "zt.\(taskId).snooze", taskId: taskId, title: title, at: later))
            default:
                break
            }
        }
    }
}

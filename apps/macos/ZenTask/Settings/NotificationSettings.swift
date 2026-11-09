import AppKit
import SwiftUI
import UserNotifications
@preconcurrency import ZenTaskShared

/// Settings → Notifications: what ZenTask notifies about and when. Stored in the synced
/// `NotificationPrefs` preference, so every device follows the same choices.
struct NotificationSettings: View {
    @Environment(AppModel.self) private var model
    @Environment(TasksModel.self) private var tasks
    @State private var prefs = NotificationPrefs.defaults
    @State private var permission: UNAuthorizationStatus?
    @State private var testSent = false

    private static let leadDays: [(Int32, String)] = [(0, "On the day only"), (1, "1 day before"), (3, "3 days before"), (7, "1 week before")]

    var body: some View {
        Form {
            Section {
                LabeledContent("Permission") {
                    switch permission {
                    case .some(.authorized), .some(.provisional), .some(.ephemeral):
                        Label("Allowed", systemImage: "checkmark.circle").foregroundStyle(.secondary)
                    case .some(.notDetermined):
                        Text("Not asked yet. Send a test notification to allow").foregroundStyle(.secondary)
                    case .some:
                        HStack {
                            ProblemLabel(text: "Turned off for ZenTask")
                            Button("Open System Settings") {
                                if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") { NSWorkspace.shared.open(url) }
                            }
                        }
                    case .none:
                        ProgressView().controlSize(.small)
                    }
                }
                HStack {
                    Spacer()
                    if testSent { Text("Sent. It shows in 5 seconds").font(.callout).foregroundStyle(.secondary) }
                    Button("Send a test notification") {
                        _Concurrency.Task {
                            testSent = await tasks.reminders?.sendTest() ?? false
                            await refreshPermission()
                        }
                    }
                }
            }
            Section("Notify me about") {
                Toggle("Task reminders", isOn: binding(\.tasks) { p, v in p.with(tasks: v) })
                Toggle("Habit reminders", isOn: binding(\.habits) { p, v in p.with(habits: v) })
                Toggle("Countdowns", isOn: binding(\.countdowns) { p, v in p.with(countdowns: v) })
                Toggle("Daily summary", isOn: binding(\.summary) { p, v in p.with(summary: v) })
                if prefs.summary {
                    TimeRow(title: "Summary at", minutes: binding(\.summaryMinutes) { p, v in p.with(summaryMinutes: v) })
                }
            }
            Section("Timing") {
                TimeRow(title: "All-day task reminders at", minutes: binding(\.allDayMinutes) { p, v in p.with(allDayMinutes: v) })
                TimeRow(title: "Countdown reminders at", minutes: binding(\.countdownMinutes) { p, v in p.with(countdownMinutes: v) })
                Picker("Remind about countdowns", selection: binding(\.countdownDaysBefore) { p, v in p.with(countdownDaysBefore: v) }) {
                    ForEach(Self.leadDays, id: \.0) { Text($0.1).tag($0.0) }
                }
            }
            Section {
                Picker("Tasks with a time", selection: binding(\.defaultTimed) { p, v in p.with(defaultTimed: v) }) {
                    presetOptions(ReminderPresets.shared.timed)
                }
                Picker("All-day tasks", selection: binding(\.defaultAllDay) { p, v in p.with(defaultAllDay: v) }) {
                    presetOptions(ReminderPresets.shared.allDay)
                }
            } header: {
                Text("Default reminder for new tasks")
            } footer: {
                Text("Added when a new task gets a date. Change it on the task any time.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            Section {
                Toggle("Play a sound", isOn: binding(\.sound) { p, v in p.with(sound: v) })
            }
        }
        .formStyle(.grouped)
        .onAppear { prefs = model.core.notificationPrefs() }
        .task { await refreshPermission() }
    }

    @ViewBuilder
    private func presetOptions(_ presets: [KotlinPair<NSString, NSString>]) -> some View {
        Text("None").tag("")
        ForEach(presets.map { ($0.first as String? ?? "", $0.second as String? ?? "") }, id: \.0) { trigger, title in
            Text(title).tag(trigger)
        }
    }

    private func refreshPermission() async {
        permission = await tasks.reminders?.permission() ?? .notDetermined
    }

    /// Reads one field and saves a changed copy.
    private func binding<V>(_ key: KeyPath<NotificationPrefs, V>, _ set: @escaping (NotificationPrefs, V) -> NotificationPrefs) -> Binding<V> {
        Binding(get: { prefs[keyPath: key] }, set: { v in
            prefs = set(prefs, v)
            model.core.setNotificationPrefs(prefs: prefs)
        })
    }
}

/// A time of day stored as minutes after midnight.
private struct TimeRow: View {
    let title: String
    @Binding var minutes: Int32

    var body: some View {
        DatePicker(title, selection: Binding(get: {
            Calendar.current.date(bySettingHour: Int(minutes) / 60, minute: Int(minutes) % 60, second: 0, of: .now) ?? .now
        }, set: { d in
            let c = Calendar.current.dateComponents([.hour, .minute], from: d)
            minutes = Int32((c.hour ?? 0) * 60 + (c.minute ?? 0))
        }), displayedComponents: .hourAndMinute)
    }
}

extension NotificationPrefs {
    static var defaults: NotificationPrefs { NotificationPrefs.companion.parse(json: "{}") }

    /// A copy with some fields changed (Kotlin data classes have no default arguments in Swift).
    func with(tasks: Bool? = nil, habits: Bool? = nil, countdowns: Bool? = nil, summary: Bool? = nil, summaryMinutes: Int32? = nil,
              allDayMinutes: Int32? = nil, countdownMinutes: Int32? = nil, countdownDaysBefore: Int32? = nil,
              defaultTimed: String? = nil, defaultAllDay: String? = nil, sound: Bool? = nil) -> NotificationPrefs {
        NotificationPrefs(tasks: tasks ?? self.tasks, habits: habits ?? self.habits, countdowns: countdowns ?? self.countdowns,
                          summary: summary ?? self.summary, summaryMinutes: summaryMinutes ?? self.summaryMinutes,
                          allDayMinutes: allDayMinutes ?? self.allDayMinutes, countdownMinutes: countdownMinutes ?? self.countdownMinutes,
                          countdownDaysBefore: countdownDaysBefore ?? self.countdownDaysBefore,
                          defaultTimed: defaultTimed ?? self.defaultTimed, defaultAllDay: defaultAllDay ?? self.defaultAllDay,
                          sound: sound ?? self.sound)
    }
}

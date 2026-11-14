import ServiceManagement
import SwiftUI

/// Settings → General: launch at login, completion sound, quick-add date recognition, which areas show.
struct GeneralSettings: View {
    @AppStorage("sound.complete") private var sound = "Pop"
    @AppStorage("quickadd.dates") private var recogniseDates = true
    @AppStorage(TasksModel.planDayAutoKey) private var planDayAuto = true
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginError: String?

    static let sounds = ["", "Pop", "Glass", "Tink", "Purr", "Hero", "Submarine"]

    var body: some View {
        Form {
            Section {
                Toggle("Open ZenTask when you log in", isOn: Binding(get: { launchAtLogin }, set: setLaunchAtLogin))
                if let loginError { Text(loginError).font(.caption).foregroundStyle(.red) }
                Picker("Sound when completing a task", selection: $sound) {
                    ForEach(Self.sounds, id: \.self) { Text($0.isEmpty ? "None" : $0).tag($0) }
                }
                .onChange(of: sound) { _, s in if !s.isEmpty { NSSound(named: NSSound.Name(s))?.play() } }
            }
            Section {
                Toggle("Recognise dates while typing a task", isOn: $recogniseDates)
            } footer: {
                Text("\"tomorrow 9am\" becomes the task's date. Off: date words stay in the title. #tags, !priority and ~list always work.")
                    .foregroundStyle(.secondary)
            }
            Section {
                Toggle("Offer \"Plan my day\" each morning", isOn: $planDayAuto)
            } footer: {
                Text("The first time you open Today each day, pick overdue and upcoming tasks to do today. Also in Today's … menu.")
                    .foregroundStyle(.secondary)
            }
            Section("Show in the sidebar rail") {
                ForEach(RailTab.allCases.filter { $0.canHide }, id: \.self) { tab in
                    FeatureToggle(tab: tab)
                }
            }
            UpdatesSection()
        }
        .formStyle(.grouped)
    }

    private func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginError = nil
        } catch {
            loginError = "macOS didn't allow it: \(error.localizedDescription). Check System Settings → General → Login Items."
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }
}

private struct FeatureToggle: View {
    let tab: RailTab
    @AppStorage private var shown: Bool

    init(tab: RailTab) {
        self.tab = tab
        _shown = AppStorage(wrappedValue: true, tab.featureKey)
    }

    var body: some View {
        Toggle(isOn: $shown) { Label(tab.title, systemImage: tab.symbol) }
    }
}

/// Settings → Date & Time: first day of the week, week numbers, lunar dates.
struct DateTimeSettings: View {
    @Environment(CalendarModel.self) private var cal
    @AppStorage("calendar.weekStart") private var weekStart = 2
    @AppStorage("calendar.weekNumbers") private var weekNumbers = false

    var body: some View {
        Form {
            Picker("Start the week on", selection: $weekStart) {
                Text("Monday").tag(2)
                Text("Sunday").tag(1)
                Text("Saturday").tag(7)
            }
            .onChange(of: weekStart) { _, _ in cal.calendar = CalendarModel.mondayFirst(); _Concurrency.Task { await cal.reload() } }
            Toggle("Show week numbers", isOn: $weekNumbers)
            Toggle("Show Vietnamese lunar dates", isOn: Binding(get: { cal.viewOptions.showLunar }, set: { cal.setViewOptions(cal.viewOptions.with(showLunar: $0)) }))
        }
        .formStyle(.grouped)
    }
}

/// Settings → General → Updates: version, check, install and relaunch.
struct UpdatesSection: View {
    @AppStorage(Updater.autoCheckKey) private var autoCheck = true
    private var updater: Updater { Updater.shared }

    var body: some View {
        Section {
            LabeledContent("Version", value: AppInfo.displayVersion)
            HStack {
                status
                Spacer()
                action
            }
            Toggle("Check for updates automatically", isOn: $autoCheck)
        } header: {
            Text("Updates")
        } footer: {
            if let checked = updater.lastChecked {
                Text("Last checked \(checked.formatted(date: .abbreviated, time: .shortened))").foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var status: some View {
        switch updater.state {
        case .idle: Text("Not checked yet").foregroundStyle(.secondary)
        case .checking: HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Checking…") }
        case .upToDate: Label("ZenTask is up to date", systemImage: "checkmark.circle").foregroundStyle(.secondary)
        case .available(let v): Label("Version \(v) is available", systemImage: "arrow.down.circle.fill").foregroundStyle(.tint)
        case .downloading: HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Downloading…") }
        case .installing: Text("Installing, ZenTask will reopen…")
        case .failed(let message): ProblemLabel(text: message)
        }
    }

    @ViewBuilder private var action: some View {
        switch updater.state {
        case .available: Button("Install and relaunch") { updater.install() }.buttonStyle(.borderedProminent)
        case .checking, .downloading, .installing: EmptyView()
        default: Button("Check now") { Task { await updater.check() } }
        }
    }
}

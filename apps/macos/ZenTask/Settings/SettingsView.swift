import SwiftUI
@preconcurrency import ZenTaskShared
import UniformTypeIdentifiers

/// The Settings panes, in sidebar order. Raw values are stored (`settings.pane`): add, never rename.
enum SettingsPane: String, CaseIterable, Identifiable {
    case general, account, ticktick, smartLists, notifications, calendars, dateTime, appearance, hotkeys
    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: "General"
        case .account: "Account"
        case .ticktick: "TickTick"
        case .smartLists: "Smart lists"
        case .notifications: "Notifications"
        case .calendars: "Calendars"
        case .dateTime: "Date & Time"
        case .appearance: "Appearance"
        case .hotkeys: "Hotkeys"
        }
    }

    var symbol: String {
        switch self {
        case .general: "gearshape"
        case .account: "person.crop.circle"
        case .ticktick: "arrow.down.circle"
        case .smartLists: "line.3.horizontal.decrease.circle"
        case .notifications: "bell.badge"
        case .calendars: "calendar"
        case .dateTime: "clock"
        case .appearance: "paintpalette"
        case .hotkeys: "command"
        }
    }

    static let storageKey = "settings.pane"
}

/// Settings window (⌘,): a sidebar of panes (nine don't fit as toolbar tabs) and grouped forms.
struct SettingsView: View {
    @AppStorage(SettingsPane.storageKey) private var paneId = SettingsPane.general.rawValue

    var body: some View {
        let pane = SettingsPane(rawValue: paneId) ?? .general
        NavigationSplitView {
            List(SettingsPane.allCases, selection: Binding(get: { pane }, set: { if let p = $0 { paneId = p.rawValue } })) { p in
                Label(p.title, systemImage: p.symbol).tag(p)
            }
            .navigationSplitViewColumnWidth(Theme.Size.settingsSidebar)
            .toolbar(removing: .sidebarToggle)
        } detail: {
            content(pane)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .navigationTitle(pane.title)
        }
        .frame(width: Theme.Size.settingsSidebar + Theme.Size.settingsWidth, height: Theme.Size.settingsHeight)
    }

    @ViewBuilder
    private func content(_ pane: SettingsPane) -> some View {
        switch pane {
        case .general: GeneralSettings()
        case .account: AccountSettings()
        case .ticktick: TickTickSettings()
        case .smartLists: SmartListSettings()
        case .notifications: NotificationSettings()
        case .calendars: CalendarSettings()
        case .dateTime: DateTimeSettings()
        case .appearance: AppearanceSettings()
        case .hotkeys: HotkeySettings()
        }
    }
}

struct AccountSettings: View {
    @Environment(AppModel.self) private var model
    @State private var password = ""

    var body: some View {
        Form {
            Section {
                LabeledContent("Server", value: AppModel.serverHost)
                if model.signedIn {
                    LabeledContent("Status") {
                        if model.syncFailed {
                            ProblemLabel(text: model.syncText)
                        } else {
                            Label(model.syncText, systemImage: "checkmark.icloud").foregroundStyle(.secondary)
                        }
                    }
                    HStack {
                        Button("Sign out", role: .destructive) { model.signOut() }
                        Spacer()
                        Button("Sync now") { model.syncNow() }
                            .keyboardShortcut("r", modifiers: .command)
                    }
                } else {
                    SecureField("Password", text: $password)
                        .onSubmit(signIn)
                    if let error = model.signInError {
                        ProblemLabel(text: error)
                    }
                    HStack {
                        Spacer()
                        if model.signingIn { ProgressView().controlSize(.small) }
                        Button("Sign in", action: signIn)
                            .keyboardShortcut(.defaultAction)
                            .disabled(password.isEmpty || model.signingIn)
                    }
                }
            } header: {
                Text("ZenTask server")
            } footer: {
                Text("Your tasks are stored on this Mac and synced to your own server. Use the password from the server's .env file")
            }
        }
        .formStyle(.grouped)
    }

    private func signIn() {
        let typed = password
        _Concurrency.Task {
            await model.signIn(password: typed)
            if model.signedIn { password = "" }
        }
    }
}

struct TickTickSettings: View {
    @Environment(AppModel.self) private var model
    @State private var cookie = ""
    @State private var choosingCsv = false

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                if model.hasCookie {
                    LabeledContent("Cookie") {
                        HStack {
                            Label("Saved in Keychain", systemImage: "key")
                                .foregroundStyle(.secondary)
                            Button("Remove", role: .destructive) { model.removeCookie() }
                        }
                    }
                } else {
                    SecureField("Value of the t cookie", text: $cookie)
                        .onSubmit(saveCookie)
                    HStack {
                        Spacer()
                        Button("Save cookie", action: saveCookie)
                            .disabled(cookie.isEmpty)
                    }
                }
            } header: {
                Text("TickTick sign-in")
            } footer: {
                Text("Copy the value of the t cookie from ticktick.com in your browser. The setup guide, section 5, shows where to find it")
            }

            Section("Sync from TickTick") {
                LabeledContent("Last sync", value: Formatting.relative(model.ticktickLastSync))
                if let message = model.ticktickMessage {
                    Label(message, systemImage: "info.circle")
                        .foregroundStyle(.secondary)
                }
                if let error = model.ticktickError {
                    ProblemLabel(text: error)
                }
                HStack {
                    if model.ticktickBusy { ProgressView().controlSize(.small) }
                    Spacer()
                    Button("Check for changes") { _Concurrency.Task { await model.previewTickTick() } }
                        .disabled(!model.hasCookie || model.ticktickBusy)
                    Button("Sync from TickTick") { _Concurrency.Task { await model.applyTickTick(confirmed: false) } }
                        .keyboardShortcut(.defaultAction)
                        .disabled(model.pending == nil || model.ticktickBusy)
                }
            }

            if model.canImportCsv {
                Section {
                    HStack {
                        Spacer()
                        Button("Import CSV backup…") { choosingCsv = true }
                    }
                } header: {
                    Text("CSV backup")
                } footer: {
                    Text("Only for a first import without a cookie. Syncing with the cookie later moves CSV-imported tasks to Trash and brings in TickTick's own copies")
                }
            }
        }
        .formStyle(.grouped)
        .fileImporter(isPresented: $choosingCsv, allowedContentTypes: [.commaSeparatedText, .plainText]) { result in
            if case .success(let url) = result { _Concurrency.Task { await model.importCsv(from: url) } }
        }
        .confirmationDialog(
            "Move \(model.pending.map { Int($0.plan.trashed) } ?? 0) items to Trash?",
            isPresented: $model.confirmMassTrash
        ) {
            Button("Move to Trash", role: .destructive) { _Concurrency.Task { await model.applyTickTick(confirmed: true) } }
            Button("Cancel", role: .cancel) { model.confirmMassTrash = false }
        } message: {
            Text("TickTick no longer returns them. They stay in ZenTask's Trash and can be restored.")
        }
    }

    private func saveCookie() {
        model.saveCookie(cookie)
        cookie = ""
    }
}

/// An error line: the icon carries the warning colour, the text stays primary for contrast.
struct ProblemLabel: View {
    let text: String

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        }
    }
}

/// Settings → Smart lists: which built-in lists the sidebar shows (as TickTick's Smart List settings).
struct SmartListSettings: View {
    @Environment(AppModel.self) private var app
    @State private var modes: [SmartList: String] = [:]

    var body: some View {
        Form {
            Section {
                ForEach(TasksModel.allSmart, id: \.self) { list in
                    Picker(selection: Binding(get: { modes[list] ?? "show" }, set: { mode in
                        app.core.setSmartListMode(list: list, mode: mode)
                        modes[list] = mode
                    })) {
                        Text("Show").tag("show")
                        Text("Show if not empty").tag("nonempty")
                        Text("Hide").tag("hide")
                    } label: {
                        Label(TasksModel.title(list), systemImage: TasksModel.symbol(list))
                    }
                }
            } header: {
                Text("Smart lists")
            } footer: {
                Text("Your own smart lists are made with the + next to Smart lists in the sidebar. These choices sync to all your devices")
            }
        }
        .formStyle(.grouped)
        .onAppear {
            modes = Dictionary(uniqueKeysWithValues: TasksModel.allSmart.map { ($0, app.core.smartListMode(list: $0)) })
        }
    }
}

import SwiftUI
import UniformTypeIdentifiers

/// Settings window (⌘,): stock tabs and grouped forms.
struct SettingsView: View {
    var body: some View {
        TabView {
            AccountSettings()
                .tabItem { Label("Account", systemImage: "person.crop.circle") }
            TickTickSettings()
                .tabItem { Label("TickTick", systemImage: "arrow.down.circle") }
            CalendarSettings()
                .tabItem { Label("Calendars", systemImage: "calendar") }
        }
        .frame(width: Theme.Size.settingsWidth)
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
        Task {
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
                    Button("Check for changes") { Task { await model.previewTickTick() } }
                        .disabled(!model.hasCookie || model.ticktickBusy)
                    Button("Sync from TickTick") { Task { await model.applyTickTick(confirmed: false) } }
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
            if case .success(let url) = result { Task { await model.importCsv(from: url) } }
        }
        .confirmationDialog(
            "Move \(model.pending.map { Int($0.plan.trashed) } ?? 0) items to Trash?",
            isPresented: $model.confirmMassTrash
        ) {
            Button("Move to Trash", role: .destructive) { Task { await model.applyTickTick(confirmed: true) } }
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

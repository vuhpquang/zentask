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
                LabeledContent("Server", value: "zentask-api.vuhpquang.com")
                if model.signedIn {
                    LabeledContent("Status") {
                        Label(model.syncText, systemImage: model.syncFailed ? "exclamationmark.triangle" : "checkmark.icloud")
                            .foregroundStyle(model.syncFailed ? .orange : .secondary)
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
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
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
                Text("Your tasks are stored on this Mac and synced to your own server. Use the password from the server's .env file.")
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
                Text("Copy the t cookie from ticktick.com in your browser. Steps: docs/setup-guide.md, section 5.")
            }

            Section("Sync from TickTick") {
                LabeledContent("Last sync", value: Formatting.relative(model.ticktickLastSync))
                if let message = model.ticktickMessage {
                    Label(message, systemImage: "info.circle")
                        .foregroundStyle(.secondary)
                }
                if let error = model.ticktickError {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
                HStack {
                    if model.ticktickBusy { ProgressView().controlSize(.small) }
                    Spacer()
                    Button("Check for changes") { Task { await model.previewTickTick() } }
                        .disabled(!model.hasCookie || model.ticktickBusy)
                    Button("Sync from TickTick") { Task { await model.applyTickTick() } }
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
                    Text("Only for a first import without a cookie. Syncing with the cookie later moves CSV-imported tasks to Trash and brings in TickTick's own copies.")
                }
            }
        }
        .formStyle(.grouped)
        .fileImporter(isPresented: $choosingCsv, allowedContentTypes: [.commaSeparatedText, .plainText]) { result in
            if case .success(let url) = result { model.importCsv(from: url) }
        }
        .confirmationDialog(
            "Move \(model.pending.map { Int($0.plan.trashed) } ?? 0) items to Trash?",
            isPresented: $model.confirmMassTrash
        ) {
            Button("Move to Trash", role: .destructive) { Task { await model.applyTickTick() } }
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

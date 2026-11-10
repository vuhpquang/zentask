import AppKit
import SwiftUI
@preconcurrency import ZenTaskShared

/// Settings → Calendars: connect Google accounts (personal, work), add iCal links, choose which
/// calendars show, remove accounts. Calendar data is read-only and lives on your server.
struct CalendarSettings: View {
    @Environment(AppModel.self) private var app
    @Environment(CalendarModel.self) private var cal
    @State private var infos: [ZenTaskCore.CalendarInfo] = []
    @State private var accounts: [CalendarAccount] = []
    @State private var busy = false
    @State private var message: String?
    @State private var error: String?
    @State private var addingIcal = false
    @State private var removing: CalendarAccount?
    @AppStorage(Theme.Calendar.workStartKey) private var workStart = Theme.Calendar.workStart
    @AppStorage(Theme.Calendar.workEndKey) private var workEnd = Theme.Calendar.workEnd

    var body: some View {
        Form {
            Section {
                Picker("Starts at", selection: $workStart) {
                    ForEach(0..<24, id: \.self) { Text(String(format: "%02d:00", $0)).tag($0) }
                }
                Picker("Ends at", selection: $workEnd) {
                    ForEach(1...24, id: \.self) { Text(String(format: "%02d:00", $0)).tag($0) }
                }
            } header: {
                Text("Working hours")
            } footer: {
                Text("Day and week views open on these hours, sized to fit the window").foregroundStyle(.secondary)
            }
            .onChange(of: workStart) { _, s in if workEnd <= s { workEnd = min(24, s + 1) } }
            .onChange(of: workEnd) { _, e in if workStart >= e { workStart = max(0, e - 1) } }
            Section {
                HStack {
                    Button("Connect Google account…", action: connect).disabled(!app.signedIn || busy)
                    Button("Add iCal link…") { addingIcal = true }.disabled(!app.signedIn || busy)
                    Spacer()
                    if busy { ProgressView().controlSize(.small) }
                    Button { refresh() } label: { Label("Refresh", systemImage: "arrow.clockwise") }.disabled(!app.signedIn || busy)
                }
                if let message { Label(message, systemImage: "info.circle").foregroundStyle(.secondary) }
                if let error { ProblemLabel(text: error) }
            } header: {
                Text("Accounts")
            } footer: {
                Text(app.signedIn ? "Google opens in your browser. If it says the app isn't verified, choose Advanced, then Go to ZenTask" : "Sign in on the Account tab first")
            }

            ForEach(accounts, id: \.id) { account in
                Section {
                    ForEach(infos.filter { $0.source.accountId == account.id }, id: \.source.id) { info in
                        Toggle(isOn: Binding(get: { !info.hidden }, set: { show in
                            app.core.setCalendarHidden(sourceId: info.source.id, hidden: !show)
                            load()
                        })) {
                            Label {
                                Text(info.source.name.isEmpty ? "Calendar" : info.source.name)
                            } icon: {
                                Circle().fill(Color(hex: info.source.color) ?? .themeAccent).frame(width: 10, height: 10)
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text(account.email)
                        if account.status == "needs_reconnect" {
                            Text("Needs reconnecting").font(.caption).foregroundStyle(.orange)
                            Button("Reconnect", action: connect)
                        }
                        Spacer()
                        Button("Remove…", role: .destructive) { removing = account }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .onAppear(perform: load)
        .task {
            // Calendars arrive with the next sync after connecting: follow record changes while open.
            for await _ in app.core.recordChanges {
                try? await _Concurrency.Task.sleep(for: .milliseconds(150))
                load()
            }
        }
        .sheet(isPresented: $addingIcal) { IcalSheet { name, url in add(name: name, url: url) } }
        .confirmationDialog("Remove \(removing?.email ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) { if let a = removing { remove(a) } }
            Button("Cancel", role: .cancel) { removing = nil }
        } message: {
            Text("Its calendars disappear from ZenTask on every device. Nothing changes in the calendar itself.")
        }
    }

    private func load() {
        infos = app.core.calendars()
        accounts = app.core.calendarAccounts().filter { !$0.deleted }
    }

    private func run(_ work: @escaping @MainActor () async throws -> String?) {
        busy = true; error = nil; message = nil
        _Concurrency.Task {
            do { message = try await work() } catch { self.error = AppModel.describe(error, server: true) }
            busy = false
            load()
            await cal.reload()
        }
    }

    private func connect() {
        run {
            let url = try await app.core.googleConnectUrl()
            if let u = URL(string: url) { NSWorkspace.shared.open(u) }
            return "Finish in your browser. Calendars appear here after the next sync"
        }
    }

    private func add(name: String, url: String) {
        run { try await app.core.addIcal(name: name, url: url); return "Added \(name)" }
    }

    private func refresh() {
        run { try await app.core.refreshCalendars(); return "Calendars refreshed" }
    }

    private func remove(_ account: CalendarAccount) {
        removing = nil
        run { try await app.core.removeCalendarAccount(id: account.id); return "Removed \(account.email)" }
    }
}

private struct IcalSheet: View {
    @Environment(\.dismiss) private var dismiss
    let add: (String, String) -> Void
    @State private var name = ""
    @State private var url = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text("Add iCal link").font(.title3.weight(.semibold))
            TextField("Name (e.g. Work)", text: $name).textFieldStyle(.roundedBorder)
            TextField("Secret address in iCal format (https://…)", text: $url).textFieldStyle(.roundedBorder)
            Text("Google Calendar: Settings → your calendar → Integrate calendar → Secret address in iCal format").font(.caption).foregroundStyle(.secondary)
            HStack {
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button("Add") { add(name.isEmpty ? "Calendar" : name, url.trimmingCharacters(in: .whitespaces)); dismiss() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(url.isEmpty)
            }
        }
        .controlSize(.large)
        .padding(Theme.Space.xl)
        .frame(width: 460)
    }
}

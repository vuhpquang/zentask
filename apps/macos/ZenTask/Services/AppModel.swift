import AppKit
import Foundation
import Observation
@preconcurrency import ZenTaskShared

/// App state: the shared core (local DB + sync + TickTick import), sign-in, and the TickTick screen.
@MainActor
@Observable
final class AppModel {
    struct PendingSync {
        let snapshot: TickTickSnapshot
        let plan: SyncPlan
    }

    let core: ZenTaskCore
    private let keychain: Keychain

    private(set) var signedIn = false
    private(set) var signingIn = false
    var signInError: String?
    private(set) var syncText = "Not signed in"
    private(set) var syncFailed = false
    private(set) var counts = Counts(lists: 0, openTasks: 0, completedTasks: 0, trashedTasks: 0)

    private(set) var hasCookie = false
    private(set) var ticktickBusy = false
    private(set) var ticktickLastSync: Date?
    private(set) var canImportCsv = true
    var ticktickMessage: String?
    var ticktickError: String?
    var pending: PendingSync?
    var confirmMassTrash = false

    init(core: ZenTaskCore, keychain: Keychain) {
        self.core = core
        self.keychain = keychain
    }

    /// The app's own model: database in Application Support, secrets in the Keychain (separate for Debug).
    static func live() -> AppModel {
        let bundleId = Bundle.main.bundleIdentifier ?? "com.vuhpquang.zentask"
        let folder = bundleId.hasSuffix(".dev") ? "ZenTask-dev" : "ZenTask"
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(folder, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let core = ZenTaskCore.companion.open(dbPath: dir.appendingPathComponent("zentask.db").path)
        return AppModel(core: core, keychain: Keychain(service: bundleId))
    }

    func start() {
        hasCookie = keychain.read(Keychain.Account.ticktickCookie) != nil
        if let token = keychain.read(Keychain.Account.serverToken) {
            core.token = token
            signedIn = true
            core.startSync()
        }
        refresh()
        _Concurrency.Task { await observeSync() }
    }

    // MARK: ZenTask server

    func signIn(password: String) async {
        guard !password.isEmpty else { return }
        signingIn = true
        signInError = nil
        defer { signingIn = false }
        do {
            let name = Host.current().localizedName ?? "Mac"
            let token = try await core.login(password: password, deviceName: name)
            try keychain.write(token, for: Keychain.Account.serverToken)
            signedIn = true
            core.startSync()
        } catch {
            signInError = Self.describe(error, server: true)
        }
    }

    func signOut() {
        core.stopSync()
        core.token = nil
        keychain.delete(Keychain.Account.serverToken)
        signedIn = false
        syncText = "Not signed in"
        syncFailed = false
    }

    func syncNow() {
        core.requestSync()
    }

    private func observeSync() async {
        for await status in core.syncStatus {
            switch onEnum(of: status) {
            case .idle:
                syncText = signedIn ? "Waiting to sync" : "Not signed in"
                syncFailed = false
            case .syncing:
                syncText = "Syncing…"
                syncFailed = false
            case .synced(let s):
                syncText = "Synced " + Formatting.relative(Date(timeIntervalSince1970: Double(s.at) / 1000))
                syncFailed = false
                refresh()
            case .failed(let f):
                syncFailed = true
                switch f.kind {
                case .unauthorized: syncText = "Signed out by the server. Sign in again"
                case .network: syncText = "Offline. Changes are saved and will sync later"
                default: syncText = "Sync failed: \(f.message)"
                }
            }
        }
    }

    // MARK: TickTick

    func saveCookie(_ value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            try keychain.write(trimmed, for: Keychain.Account.ticktickCookie)
            hasCookie = true
            ticktickError = nil
        } catch {
            ticktickError = error.localizedDescription
        }
    }

    func removeCookie() {
        keychain.delete(Keychain.Account.ticktickCookie)
        hasCookie = false
        pending = nil
    }

    /// Downloads from TickTick and shows what a sync would change; nothing is written yet.
    func previewTickTick() async {
        guard let cookie = keychain.read(Keychain.Account.ticktickCookie) else { return }
        ticktickBusy = true
        ticktickError = nil
        ticktickMessage = nil
        pending = nil
        defer { ticktickBusy = false }
        do {
            let snapshot = try await core.ticktickSnapshot(cookie: cookie)
            let plan = try await core.ticktickPreview(snapshot: snapshot)
            pending = PendingSync(snapshot: snapshot, plan: plan)
            ticktickMessage = Self.summary(plan)
        } catch {
            ticktickError = Self.describe(error, server: false)
        }
    }

    func applyTickTick() async {
        guard let pending else { return }
        if pending.plan.needsConfirmation && !confirmMassTrash {
            confirmMassTrash = true
            return
        }
        ticktickBusy = true
        defer { ticktickBusy = false; confirmMassTrash = false }
        do {
            let plan = try await core.ticktickApply(snapshot: pending.snapshot, confirmMassTrash: pending.plan.needsConfirmation)
            self.pending = nil
            ticktickMessage = "Done: " + Self.summary(plan)
            refresh()
        } catch {
            ticktickError = Self.describe(error, server: false)
        }
    }

    func importCsv(from url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let text = try String(contentsOf: url, encoding: .utf8)
            let plan = try core.importCsv(text: text)
            ticktickError = nil
            ticktickMessage = "Imported: " + Self.summary(plan)
            refresh()
        } catch {
            ticktickError = Self.describe(error, server: false)
        }
    }

    func refresh() {
        counts = core.counts()
        ticktickLastSync = core.ticktickLastSyncAt.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) }
        canImportCsv = !core.ticktickHasApiData
    }

    // MARK: Text

    private static func summary(_ plan: SyncPlan) -> String {
        Formatting.plan(created: Int(plan.created), updated: Int(plan.updated), trashed: Int(plan.trashed), restored: Int(plan.restored))
    }

    /// What happened and what to do next.
    static func describe(_ error: Error, server: Bool) -> String {
        let kotlin = (error as NSError).userInfo["KotlinException"]
        if let e = kotlin as? SyncException {
            switch e.kind {
            case .unauthorized: return "Wrong password"
            case .network: return "Can't reach the ZenTask server. Check your connection and try again"
            default: return e.message ?? "The server answered with an error"
            }
        }
        if let e = kotlin as? TickTickException {
            switch e.kind {
            case .cookieExpired: return "The TickTick cookie has expired. Copy a new one from ticktick.com"
            case .network: return "Can't reach TickTick. Check your connection and try again"
            default: return "TickTick answered unexpectedly: \(e.message ?? "unknown error")"
            }
        }
        if let e = kotlin as? KotlinThrowable {
            return e.message ?? (server ? "Sign-in failed" : "Import failed")
        }
        return error.localizedDescription
    }
}

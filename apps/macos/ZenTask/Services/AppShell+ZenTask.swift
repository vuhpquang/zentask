import AboutKit
import AppShellCore
import SwiftUI
import UpdaterKit

// ZenTask's values for the shared app shell (Vendor/app-shell): who it is, where its releases are and
// where the update setting lives (`updates.autoCheck` in UserDefaults — the key stays as it was).

extension AppIdentity {
    static let zenTask = AppIdentity(
        name: "ZenTask",
        tagline: "Your tasks, habits and calendar, calm and in one place.",
        repo: "vuhpquang/zentask",
        firstYear: 2026,
        whatsNew: .automatic
    )
}

extension Updater {
    /// ZenTask's one updater: GitHub release `vX.Y.Z` with `ZenTask-X.Y.Z.zip` (the APKs beside it are ignored).
    /// No auto-install option: an update is always installed by the user.
    @MainActor static let shared = Updater(
        app: .zenTask,
        matcher: .zip(prefix: "ZenTask-"),
        settings: UserDefaultsUpdateSettings(autoCheckKey: "updates.autoCheck", autoInstallKey: nil)
    )
}

extension WhatsNewTracker {
    /// ZenTask has a main window, so What's New opens by itself on the first launch of a new version.
    @MainActor static let shared = WhatsNewTracker(app: .zenTask)

    /// Call once at launch: records the version, and shows What's New after an update.
    @MainActor static func showAfterUpdate() {
        shared.evaluateLaunch()
        guard let version = shared.pendingVersion else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            WhatsNewWindow.show(app: .zenTask, version: version, tracker: shared)
        }
    }
}

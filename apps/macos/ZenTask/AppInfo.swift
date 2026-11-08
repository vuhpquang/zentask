import Foundation
import ZenTaskShared

/// Version info for the UI; app (bundle) and core (Kotlin shared) versions must match.
enum AppInfo {
    static var sharedVersion: String { ZenTaskInfo.shared.version }

    static var bundleVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    static var displayVersion: String {
        let name = ZenTaskInfo.shared.appName
        return bundleVersion == sharedVersion
            ? "\(name) \(sharedVersion)"
            : "\(name) app \(bundleVersion) / core \(sharedVersion)"
    }
}

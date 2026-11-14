import AppShellCore
import UpdaterKit
import XCTest
@testable import ZenTask

/// The updater itself is tested in app-shell; here: ZenTask's values for it.
@MainActor
final class UpdaterTests: XCTestCase {
    func testZenTaskUpdaterConfiguration() {
        XCTAssertEqual(AppIdentity.zenTask.repo, "vuhpquang/zentask")
        XCTAssertEqual(AppIdentity.zenTask.whatsNew, .automatic)
        XCTAssertEqual(Updater.shared.matcher, .zip(prefix: "ZenTask-"))
        XCTAssertFalse(Updater.shared.settings.supportsAutoInstall)
    }

    func testPicksTheZipNotTheAPK() {
        let assets = [Release.Asset(name: "ZenTask-1.0.0.apk", url: URL(string: "https://x/a.apk")!),
                      Release.Asset(name: "ZenTask-1.0.0.zip", url: URL(string: "https://x/a.zip")!)]
        XCTAssertEqual(Updater.shared.matcher.pick(assets)?.name, "ZenTask-1.0.0.zip")
    }
}

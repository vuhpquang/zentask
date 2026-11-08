import XCTest
@testable import ZenTask

final class AppInfoTests: XCTestCase {
    func testBundleVersionMatchesSharedVersion() {
        XCTAssertEqual(AppInfo.bundleVersion, AppInfo.sharedVersion)
    }

    func testDisplayVersion() {
        XCTAssertEqual(AppInfo.displayVersion, "ZenTask \(AppInfo.sharedVersion)")
    }
}

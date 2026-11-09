import XCTest
@testable import ZenTask

final class UpdaterTests: XCTestCase {
    func testComparesDottedVersions() {
        XCTAssertTrue(Updater.isVersion("0.10.0", newerThan: "0.9.0"))
        XCTAssertTrue(Updater.isVersion("1.0.0", newerThan: "0.99.9"))
        XCTAssertTrue(Updater.isVersion("0.9.1", newerThan: "0.9"))
        XCTAssertFalse(Updater.isVersion("0.9.0", newerThan: "0.9.0"))
        XCTAssertFalse(Updater.isVersion("0.8.9", newerThan: "0.9.0"))
        XCTAssertFalse(Updater.isVersion("0.9.0-beta", newerThan: "0.9.0"))
    }
}

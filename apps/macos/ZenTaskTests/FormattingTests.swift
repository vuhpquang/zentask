import XCTest
@testable import ZenTask

final class FormattingTests: XCTestCase {
    func testPlanSummary() {
        XCTAssertEqual(Formatting.plan(created: 0, updated: 0, trashed: 0, restored: 0), "Already up to date")
        XCTAssertEqual(Formatting.plan(created: 12, updated: 1, trashed: 0, restored: 0), "12 new, 1 updated")
        XCTAssertEqual(Formatting.plan(created: 0, updated: 0, trashed: 3, restored: 2), "3 to Trash, 2 restored")
    }
}

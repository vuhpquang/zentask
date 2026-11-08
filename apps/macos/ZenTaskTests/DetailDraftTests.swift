import XCTest
@testable import ZenTask

final class DetailDraftTests: XCTestCase {
    func testUntouchedFieldIsNeverWrittenEvenIfTheTaskChanged() {
        var draft = DetailDraft(title: "Old", notes: "old notes")
        // Sync changes the task while the pane is open; the user didn't touch the fields.
        XCTAssertNil(draft.titleToSave(current: "Remote title"))
        XCTAssertNil(draft.notesToSave(current: "remote notes"))
        // When not editing, the draft follows the task.
        draft.follow(title: "Remote title", notes: "remote notes", editing: nil)
        XCTAssertEqual(draft.title, "Remote title")
        XCTAssertEqual(draft.notes, "remote notes")
    }

    func testUserEditIsSaved() {
        var draft = DetailDraft(title: "Old", notes: "")
        draft.title = "New"
        XCTAssertEqual(draft.titleToSave(current: "Old"), "New")
        XCTAssertNil(draft.titleToSave(current: "New"), "already saved")
        draft.title = "   "
        XCTAssertNil(draft.titleToSave(current: "Old"), "an empty title is not saved")
    }

    func testFieldBeingEditedIsNotReplacedBySync() {
        var draft = DetailDraft(title: "Old", notes: "mine")
        draft.notes = "typing…"
        draft.follow(title: "Remote", notes: "remote notes", editing: .notes)
        XCTAssertEqual(draft.notes, "typing…")
        XCTAssertEqual(draft.title, "Remote")
    }
}

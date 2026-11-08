import XCTest
import ZenTaskShared

/// The shared store works from Swift: native SQLite driver linked, data persists across reopen.
final class StoreSmokeTests: XCTestCase {
    func testTaskPersistsAcrossReopen() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("zentask.db").path

        let core = ZenTaskCore.companion.open(dbPath: path)
        core.putTask(task: core.draftTask(title: "Hello", now: 1))
        core.close()

        let reopened = ZenTaskCore.companion.open(dbPath: path)
        defer { reopened.close() }
        XCTAssertEqual(reopened.tasks().map(\.title), ["Hello"])
        XCTAssertEqual(reopened.tasks().first?.projectId, "inbox")
    }
}

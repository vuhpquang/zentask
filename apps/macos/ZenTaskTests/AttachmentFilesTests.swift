import AppKit
import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class AttachmentFilesTests: XCTestCase {
    private func temp() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("zt-files-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func testImagesAreShrunkToJpegAndRecorded() throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let files = AttachmentFiles(core: core, folder: temp().appendingPathComponent("files"))
        let task = core.actions.create(title: "Receipt", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        // A 4000×3000 PNG.
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 4000, pixelsHigh: 3000, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        let png = temp().appendingPathComponent("photo.png")
        try rep.representation(using: .png, properties: [:])!.write(to: png)
        try files.add(png, to: task.id)
        let a = try XCTUnwrap(core.attachmentActions.of(taskId: task.id).first)
        XCTAssertEqual(a.name, "photo.jpg")
        XCTAssertEqual(a.mime, "image/jpeg")
        XCTAssertEqual(max(a.width, a.height), 2560)
        XCTAssertNotNil(files.url(a.fileId))
    }

    func testFilesOverTenMegabytesAreRefused() throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let files = AttachmentFiles(core: core, folder: temp().appendingPathComponent("files"))
        let task = core.actions.create(title: "Big", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        let big = temp().appendingPathComponent("big.bin")
        try Data(count: AttachmentFiles.maxBytes + 1).write(to: big)
        XCTAssertThrowsError(try files.add(big, to: task.id))
        XCTAssertTrue(core.attachmentActions.of(taskId: task.id).isEmpty)
    }
}

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

    func testSmallImagesAreKeptAsIs() throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let files = AttachmentFiles(core: core, folder: temp().appendingPathComponent("files"))
        let task = core.actions.create(title: "Icon", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 4000, pixelsHigh: 3000, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        let png = temp().appendingPathComponent("photo.png")
        let bytes = rep.representation(using: .png, properties: [:])!
        try bytes.write(to: png)
        try files.add(png, to: task.id)
        let a = try XCTUnwrap(core.attachmentActions.of(taskId: task.id).first)
        XCTAssertEqual(a.name, "photo.png")
        XCTAssertEqual(a.mime, "image/png")
        XCTAssertEqual(a.size, Int64(bytes.count))
        XCTAssertEqual(a.width, 4000)
    }

    func testOnlyImagesOverTenMegabytesAreShrunk() throws {
        let core = ZenTaskCore.companion.open(dbPath: nil)
        let files = AttachmentFiles(core: core, folder: temp().appendingPathComponent("files"))
        let task = core.actions.create(title: "Receipt", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        // A 4000×3000 PNG of noise: well over 10 MB.
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 4000, pixelsHigh: 3000, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        if let p = rep.bitmapData { for i in 0..<(rep.bytesPerRow * 3000) { p[i] = UInt8.random(in: 0...255) } }
        let png = temp().appendingPathComponent("photo.png")
        let bytes = rep.representation(using: .png, properties: [:])!
        XCTAssertGreaterThan(bytes.count, AttachmentFiles.maxBytes)
        try bytes.write(to: png)
        try files.add(png, to: task.id)
        let a = try XCTUnwrap(core.attachmentActions.of(taskId: task.id).first)
        XCTAssertEqual(a.name, "photo.jpg")
        XCTAssertEqual(a.mime, "image/jpeg")
        XCTAssertLessThanOrEqual(a.size, Int64(AttachmentFiles.maxBytes))
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

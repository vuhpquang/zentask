import AppKit
import CryptoKit
import ImageIO
import UniformTypeIdentifiers
@preconcurrency import ZenTaskShared

/// Attachment bytes on this Mac: a cache folder (`Application Support/ZenTask[-dev]/files/<sha256>`), images
/// shrunk on the way in, and a sync pass that uploads what the server lacks and downloads what this Mac lacks
/// (every attachment is kept offline). Records themselves sync like everything else.
@MainActor
@Observable
final class AttachmentFiles {
    enum AddError: LocalizedError {
        case tooLarge(String), unreadable(String)
        var errorDescription: String? {
            switch self {
            case .tooLarge(let n): "\(n) is larger than 10 MB. Attach a smaller file."
            case .unreadable(let n): "Couldn't read \(n)."
            }
        }
    }

    private let core: ZenTaskCore
    let folder: URL
    private(set) var cached: Set<String> = []
    private(set) var syncing = false
    var lastError: String?
    private var pending: _Concurrency.Task<Void, Never>?

    static let maxBytes = 10 * 1024 * 1024
    private static let maxImageSide: CGFloat = 2560

    /// [folder]: the cache (tests pass a temporary one).
    init(core: ZenTaskCore, folder: URL? = nil) {
        self.core = core
        let dev = Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true
        self.folder = folder ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(dev ? "ZenTask-dev" : "ZenTask", isDirectory: true).appendingPathComponent("files", isDirectory: true)
        try? FileManager.default.createDirectory(at: self.folder, withIntermediateDirectories: true)
        cached = Set((try? FileManager.default.contentsOfDirectory(atPath: self.folder.path)) ?? []).filter { $0.count == 64 }
    }

    func url(_ fileId: String) -> URL? { cached.contains(fileId) ? folder.appendingPathComponent(fileId) : nil }

    /// Adds [url] to the task as is when it fits in 10 MB; only a larger image is shrunk (JPEG, smaller steps until it fits).
    func add(_ url: URL, to taskId: String) throws {
        let name = url.lastPathComponent
        guard var data = try? Data(contentsOf: url) else { throw AddError.unreadable(name) }
        var mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        var fileName = name
        var size = CGSize.zero
        let type = UTType(filenameExtension: url.pathExtension)
        if type?.conforms(to: .image) == true {
            size = Self.pixelSize(data) ?? .zero
            if data.count > Self.maxBytes, let shrunk = Self.shrinkToFit(data) {
                data = shrunk.data; size = shrunk.size; mime = "image/jpeg"
                if type != .jpeg { fileName = (name as NSString).deletingPathExtension + ".jpg" }
            }
        }
        guard data.count <= Self.maxBytes else { throw AddError.tooLarge(name) }
        let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        try data.write(to: folder.appendingPathComponent(sha), options: .atomic)
        cached.insert(sha)
        _ = core.attachmentActions.add(taskId: taskId, fileId: sha, name: fileName, mime: mime, size: Int64(data.count),
                                       width: Int32(size.width), height: Int32(size.height))
        syncSoon()
    }

    static func pixelSize(_ data: Data) -> CGSize? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return CGSize(width: w, height: h)
    }

    /// An image over 10 MB made to fit: JPEG at the original size first, then smaller sides until it's under 10 MB.
    static func shrinkToFit(_ data: Data) -> (data: Data, size: CGSize)? {
        let full = pixelSize(data).map { max($0.width, $0.height) } ?? 8000
        for side in [full, 6000, 4096, 3072, 2560, 2048] where side <= full {
            if let out = shrink(data, maxSide: side), out.data.count <= maxBytes { return out }
        }
        return nil
    }

    /// JPEG at quality 0.85, longest side at most [maxSide] px; nil if it isn't a readable image.
    static func shrink(_ data: Data, maxSide: CGFloat = maxImageSide) -> (data: Data, size: CGSize)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return (out as Data, CGSize(width: image.width, height: image.height))
    }

    /// Debounced sync pass (after data changes or an add).
    func syncSoon() {
        pending?.cancel()
        pending = _Concurrency.Task {
            try? await _Concurrency.Task.sleep(for: .seconds(1))
            guard !_Concurrency.Task.isCancelled else { return }
            await sync()
        }
    }

    /// Uploads cached files the server lacks, downloads needed files this Mac lacks. Needs a signed-in server.
    func sync() async {
        guard !syncing, let token = core.token else { return }
        syncing = true
        defer { syncing = false }
        let base = URL(string: ZenTaskCore.companion.DEFAULT_SERVER)!.appendingPathComponent("v1/files")
        let needed = core.attachmentActions.neededFileIds()
        do {
            let local = needed.intersection(cached)
            if !local.isEmpty {
                var req = URLRequest(url: base.appendingPathComponent("missing"))
                req.httpMethod = "POST"
                req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.httpBody = try JSONEncoder().encode(["ids": Array(local)])
                let (data, _) = try await URLSession.shared.data(for: req)
                let missing = (try? JSONDecoder().decode([String: [String]].self, from: data))?["missing"] ?? []
                for id in missing {
                    var put = URLRequest(url: base.appendingPathComponent(id))
                    put.httpMethod = "PUT"
                    put.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    let (_, resp) = try await URLSession.shared.upload(for: put, fromFile: folder.appendingPathComponent(id))
                    if let code = (resp as? HTTPURLResponse)?.statusCode, code == 507 { lastError = "The server is out of space for attachments." }
                }
            }
            for id in core.attachmentActions.toDownload(cached: cached) {
                var get = URLRequest(url: base.appendingPathComponent(id))
                get.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                let (data, resp) = try await URLSession.shared.data(for: get)
                guard (resp as? HTTPURLResponse)?.statusCode == 200,
                      SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == id else { continue }
                try data.write(to: folder.appendingPathComponent(id), options: .atomic)
                cached.insert(id)
            }
        } catch {
            lastError = nil // offline: try again on the next change or launch
        }
    }
}

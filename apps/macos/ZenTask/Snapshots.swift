import AppKit
import SwiftUI
@preconcurrency import ZenTaskShared

/// `ZENTASK_SNAPSHOT=<dir> ZenTask.app/Contents/MacOS/ZenTask` renders every screen in light and dark
/// to PNGs (offscreen, in-memory database, no Keychain) and exits. Used by the design check.
@MainActor
enum Snapshots {
    static func render(to dir: URL) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let model = AppModel(core: ZenTaskCore.companion.open(dbPath: nil),
                             keychain: Keychain(service: "com.vuhpquang.zentask.snapshot"))
        let screens: [(String, AnyView, CGSize)] = [
            ("main-empty", AnyView(RootView()), CGSize(width: 900, height: 560)),
            ("settings-account", AnyView(AccountSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 300)),
            ("settings-ticktick", AnyView(TickTickSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 520)),
        ]
        for (name, view, size) in screens {
            for (suffix, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
                write(view.environment(model), size: size, appearance: appearance, to: dir.appendingPathComponent("\(name)-\(suffix).png"))
            }
        }
        if let csv = sampleCsv() {
            let semaphore = DispatchSemaphore(value: 0)
            let core = model.core
            _Concurrency.Task.detached { _ = try? await core.importCsv(text: csv); semaphore.signal() }
            while semaphore.wait(timeout: .now()) == .timedOut { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) }
            model.refresh()
            model.ticktickMessage = "Imported: 824 new"
            for (suffix, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
                write(AnyView(RootView()).environment(model), size: CGSize(width: 900, height: 560), appearance: appearance,
                      to: dir.appendingPathComponent("main-imported-\(suffix).png"))
            }
        }
    }

    private static func sampleCsv() -> String? {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/TickTick-backup-2026-11-08.csv")
        return try? String(contentsOf: url, encoding: .utf8)
    }

    private static func write(_ view: some View, size: CGSize, appearance: NSAppearance.Name, to url: URL) {
        let host = NSHostingView(rootView: view.frame(width: size.width, height: size.height).background(Color(nsColor: .windowBackgroundColor)))
        host.appearance = NSAppearance(named: appearance)
        host.frame = CGRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: appearance)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
        host.cacheDisplay(in: host.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
    }
}

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
        let tasks = TasksModel(core: model.core)
        let settings: [(String, AnyView, CGSize)] = [
            ("settings-account", AnyView(AccountSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 300)),
            ("settings-ticktick", AnyView(TickTickSettings().frame(width: Theme.Size.settingsWidth)), CGSize(width: 540, height: 520)),
        ]
        for (name, view, size) in settings { both(view.environment(model), size: size, name: name, dir: dir) }
        wait { await tasks.reload() }
        both(AnyView(MainWindow()).environment(model).environment(tasks), size: windowSize, name: "tasks-empty", dir: dir)

        if let csv = sampleCsv() {
            let core = model.core
            wait { _ = try? await core.importCsv(text: csv) }
            wait { await tasks.reload() }
            let shots: [(String, SidebarItem)] = [("tasks-inbox", .smart(.inbox)), ("tasks-all", .smart(.all)), ("tasks-completed", .smart(.completed))]
            for (name, item) in shots {
                tasks.sidebar = item
                wait { await tasks.settle() }
                both(AnyView(MainWindow()).environment(model).environment(tasks), size: windowSize, name: name, dir: dir)
            }
            if let project = tasks.listProjects.first(where: { $0.viewMode == "kanban" }) ?? tasks.listProjects.first {
                tasks.sidebar = .project(project.id)
                wait { await tasks.settle() }
                both(AnyView(MainWindow()).environment(model).environment(tasks), size: windowSize, name: "tasks-list", dir: dir)
            }
            tasks.sheet = nil
            for (name, view) in [("sheet-list", AnyView(ListEditor(project: nil))), ("sheet-filter", AnyView(FilterEditor(filter: nil)))] {
                both(view.environment(model).environment(tasks), size: CGSize(width: 480, height: 620), name: name, dir: dir)
            }
            tasks.sidebar = .smart(.all)
            wait { await tasks.settle() }
            if let checklist = tasks.sections.flatMap(\.rows).first(where: { $0.checklistTotal > 0 }) ?? tasks.sections.first?.rows.first {
                tasks.selection = checklist.task.id
                both(AnyView(MainWindow()).environment(model).environment(tasks), size: windowSize, name: "tasks-detail", dir: dir)
            }
        }
    }

    private static let windowSize = CGSize(width: 1100, height: 720)

    private static func both(_ view: some View, size: CGSize, name: String, dir: URL) {
        for (suffix, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            write(view, size: size, appearance: appearance, to: dir.appendingPathComponent("\(name)-\(suffix).png"))
        }
    }

    /// Runs async work to completion while keeping the main run loop turning.
    private static func wait(_ work: @escaping @MainActor () async -> Void) {
        var done = false
        _Concurrency.Task { @MainActor in await work(); done = true }
        while !done { RunLoop.current.run(until: Date().addingTimeInterval(0.02)) }
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

import AppKit
import SwiftUI

/// The menu bar item: an `NSStatusItem` whose popover shows `MenuBarPanel`. AppKit instead of a `MenuBarExtra`
/// scene because that scene keeps the XCTest runner from connecting to the test host. Follows the
/// Settings → General switch (UserDefaults `menubar.show`).
@MainActor
final class MenuBarController: NSObject, NSPopoverDelegate {
    static let shared = MenuBarController()

    private var item: NSStatusItem?
    private let popover = NSPopover()
    private var tasks: TasksModel?
    private var observer: NSObjectProtocol?

    func start(tasks: TasksModel) {
        guard self.tasks == nil else { return }
        self.tasks = tasks
        // Load the data at launch even with no window open: the menu bar and the desktop widgets show it.
        tasks.start()
        popover.behavior = .transient
        popover.delegate = self
        popover.contentViewController = NSHostingController(rootView: MenuBarPanel().environment(tasks).modifier(Themed()))
        update()
        observer = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { MenuBarController.shared.update() }
        }
    }

    private var wanted: Bool { UserDefaults.standard.object(forKey: Theme.MenuBar.showKey) as? Bool ?? true }

    private func update() {
        if wanted, item == nil {
            let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            item.button?.image = NSImage(systemSymbolName: "checkmark.circle", accessibilityDescription: "ZenTask Today")
            item.button?.target = self
            item.button?.action = #selector(toggle(_:))
            self.item = item
        } else if !wanted, let item {
            popover.performClose(nil)
            NSStatusBar.system.removeStatusItem(item)
            self.item = nil
        }
    }

    @objc private func toggle(_ sender: NSStatusBarButton) {
        if popover.isShown {
            popover.performClose(sender)
        } else {
            tasks?.start()
            popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
        }
    }
}

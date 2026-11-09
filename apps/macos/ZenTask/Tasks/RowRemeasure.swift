import AppKit
import SwiftUI

/// Works around a macOS `List` bug: rows filled while the list isn't on screen (the app relaunching
/// behind other windows after an update, a covered window while sync changes tasks) keep their estimated
/// height (`defaultMinListRowHeight`), so a row's second line is drawn under the next row. Switching
/// lists fixed it because that rebuilds the rows; this rebuilds them once at the moments it goes wrong:
/// when the first data arrives, and when the window comes back after the data changed behind it.
struct RemeasureRows<Key: Equatable>: ViewModifier {
    /// Changes whenever the rows change (the task index).
    let data: Key
    /// True once there are rows to show.
    let hasRows: Bool

    @State private var epoch = 0
    @State private var visible = true
    @State private var staleWhileHidden = false
    @State private var firstRowsShown = false

    func body(content: Content) -> some View {
        content
            .id(epoch)
            .background(WindowVisibilityReader { now in
                visible = now
                if now && staleWhileHidden {
                    staleWhileHidden = false
                    epoch += 1
                }
            })
            .onChange(of: data) {
                if !visible { staleWhileHidden = true }
            }
            .onChange(of: hasRows, initial: true) { _, has in
                guard has, !firstRowsShown else { return }
                firstRowsShown = true
                // After this layout pass, so the rebuilt rows are measured in the real window.
                DispatchQueue.main.async { epoch += 1 }
            }
    }
}

extension View {
    func remeasuresRows<Key: Equatable>(data: Key, hasRows: Bool) -> some View {
        modifier(RemeasureRows(data: data, hasRows: hasRows))
    }
}

/// Reports whether the hosting window is visible on screen (not minimised, hidden or fully covered).
private struct WindowVisibilityReader: NSViewRepresentable {
    let changed: (Bool) -> Void

    func makeNSView(context: Context) -> VisibilityView {
        let view = VisibilityView()
        view.changed = changed
        return view
    }

    func updateNSView(_ view: VisibilityView, context: Context) {
        view.changed = changed
    }

    final class VisibilityView: NSView {
        var changed: ((Bool) -> Void)?

        override func viewWillMove(toWindow newWindow: NSWindow?) {
            super.viewWillMove(toWindow: newWindow)
            NotificationCenter.default.removeObserver(self, name: NSWindow.didChangeOcclusionStateNotification, object: nil)
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            // Selector-based observers are dropped automatically when the view goes away.
            NotificationCenter.default.addObserver(self, selector: #selector(occlusionChanged), name: NSWindow.didChangeOcclusionStateNotification, object: window)
            report()
        }

        @objc private func occlusionChanged(_ note: Notification) { report() }

        private func report() {
            guard let window else { return }
            let visible = window.occlusionState.contains(.visible)
            // Not during SwiftUI's own update (moving into a window happens inside one).
            DispatchQueue.main.async { [weak self] in self?.changed?(visible) }
        }
    }
}

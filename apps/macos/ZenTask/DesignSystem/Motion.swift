import AppKit
import SwiftUI

/// The app's one animation switch (design rule): every animated change goes through here, so Reduce Motion
/// turns movement off everywhere at once.
@MainActor
enum Motion {
    static var reduced: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    /// Folds, panels and overlays.
    static var standard: Animation? { reduced ? nil : Theme.List.foldAnimation }

    /// A panel sliding in from an edge, or just fading with Reduce Motion.
    static func slide(_ edge: Edge) -> AnyTransition {
        reduced ? .opacity : .move(edge: edge).combined(with: .opacity)
    }
}

/// `withAnimation` that respects Reduce Motion.
@MainActor
func withMotion<Result>(_ body: () throws -> Result) rethrows -> Result {
    try withAnimation(Motion.standard, body)
}

extension EnvironmentValues {
    /// The search overlay is open: single-key shortcuts (calendar D/W/M, 1–5) must not eat typing.
    @Entry var searchOpen: Bool = false
}

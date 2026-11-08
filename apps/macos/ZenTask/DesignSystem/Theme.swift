import SwiftUI

/// ZenTask design tokens: spacing on a 2/4/8 scale, radii, sizes. Views use these, never literals.
enum Theme {
    enum Space {
        static let xxs: CGFloat = 2
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
    }

    enum Radius {
        static let card: CGFloat = 16
        static let tile: CGFloat = 10
    }

    /** Priority colours (ZenTask's own): system colours, so they resolve per appearance. */
    enum PriorityColor {
        static func color(_ priority: Int32) -> Color {
            switch priority {
            case 5: .red
            case 3: .orange
            case 1: .blue
            default: .secondary
            }
        }
    }

    enum Opacity {
        static let field: Double = 0.6
        static let chip: Double = 0.15
        static let pill: Double = 0.18
    }

    enum Row {
        static let checkbox: CGFloat = 16
        static let indent: CGFloat = 24
        static let pillRadius: CGFloat = 6
    }

    /** The always-visible icon rail on the left of the window (spec §11). */
    enum Rail {
        static let width: CGFloat = 64
        static let hit: CGFloat = 44
        static let icon: CGFloat = 22
        static let radius: CGFloat = 12
        /// Clears the window's traffic lights.
        static let topInset: CGFloat = 52
    }

    enum Size {
        static let heroIcon: CGFloat = 44
        static let summaryWidth: CGFloat = 420
        static let settingsWidth: CGFloat = 540
        static let sidebarMin: CGFloat = 200
        static let listMin: CGFloat = 340
        static let detailMin: CGFloat = 320
    }
}

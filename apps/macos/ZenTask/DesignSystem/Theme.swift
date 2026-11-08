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

    enum Row {
        static let checkbox: CGFloat = 16
        static let indent: CGFloat = 24
        static let pillRadius: CGFloat = 6
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

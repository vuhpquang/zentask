import AppKit
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
        static func nsColor(_ priority: Int32) -> NSColor {
            switch priority {
            case 5: .systemRed
            case 3: .systemOrange
            case 1: .systemBlue
            default: .secondaryLabelColor
            }
        }

        static func color(_ priority: Int32) -> Color {
            switch priority {
            case 5: .red
            case 3: .orange
            case 1: .blue
            default: .secondary
            }
        }
    }

    enum Detail {
        static let chipHeight: CGFloat = 30
        static let cardRadius: CGFloat = 12
        static let cardPadding: CGFloat = 14
        static let maxWidth: CGFloat = 640
    }

    enum Calendar {
        static let hourHeight: CGFloat = 52
        static let minHourHeight: CGFloat = 28
        static let maxHourHeight: CGFloat = 160
        static let modeMenuWidth: CGFloat = 130
        static let dot: CGFloat = 7
        static let popoverWidth: CGFloat = 300
        static let labelNudge: CGFloat = 7
        static let yearCell: CGFloat = 22
        static let timeGutter: CGFloat = 56
        static let allDayMin: CGFloat = 30
        static let panelWidth: CGFloat = 280
        static let eventRadius: CGFloat = 6
        static let eventFill: Double = 0.22
        static let monthCellMin: CGFloat = 96
        static let monthItems = 4
        static let headerHeight: CGFloat = 44
        static let snapMinutes = 15
    }

    enum Opacity {
        static let field: Double = 0.6
        static let chip: Double = 0.15
        static let pill: Double = 0.18
        static let card: Double = 0.5
        static let chipFill: Double = 0.14
    }

    enum Row {
        static let checkbox: CGFloat = 19
        static let verticalPadding: CGFloat = 7
        static let metaSpacing: CGFloat = 3
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
        static let sheetWidth: CGFloat = 380
        static let minRow: CGFloat = 40
        static let tagField: CGFloat = 90
        static let pickerWidth: CGFloat = 240
        static let pickerMaxHeight: CGFloat = 360
        static let filterWidth: CGFloat = 460
        static let filterFormHeight: CGFloat = 420
        static let swatch: CGFloat = 24
        static let listMin: CGFloat = 340
        static let detailMin: CGFloat = 320
    }
}

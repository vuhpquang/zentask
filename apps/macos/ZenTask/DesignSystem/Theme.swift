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
        static let datePopoverWidth: CGFloat = 300
    }

    enum Calendar {
        static let hourHeight: CGFloat = 52
        static let minHourHeight: CGFloat = 28
        static let maxHourHeight: CGFloat = 160
        static let modeMenuWidth: CGFloat = 130
        static let modePickerWidth: CGFloat = 300
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
        static let sideBarWidth: CGFloat = 260
        static let weekNumber: CGFloat = 26
        /// Length of a dropped task's block (matches CalendarQueries.TASK_MINUTES).
        static let taskMinutes = 30
        static let ghostMin: CGFloat = 20
        static let miniDay: CGFloat = 24
        static let miniDot: CGFloat = 4
        static let lunarSize: CGFloat = 8
        static let optionsHeight: CGFloat = 640
        static let swatch: CGFloat = 18
        static let stylePreview: CGFloat = 130
        /// Block fills: Classic is solid, Modern a tint with a coloured edge.
        static let classicFill: Double = 0.9
        static let modernFill: Double = 0.2
        /// Blocks shorter than this show title and time on one line.
        static let compactBlock: CGFloat = 34
        /// Space between stacked blocks, so neighbours of the same colour stay apart.
        static let blockGap: CGFloat = 2
        static let minBlock: CGFloat = 16
        /// Narrower blocks keep the title on one line.
        static let wrapWidth: CGFloat = 110
        /// Narrower blocks drop the kind icon so the title has room.
        static let iconMinWidth: CGFloat = 80
        static let labelRoom: CGFloat = 10
        /// A compact block keeps its time only if the title still gets this much room.
        static let minTitle: CGFloat = 44
        static let heatMin: Double = 0.15
        static let heatStep: Double = 0.12
        static let heatRadius: CGFloat = 5
        static let fixedColors = ["#F2A65A", "#B8D45A", "#7FCF7A", "#5EC4B6", "#6A9CF0", "#A88BF2", "#EE82B8", "#EB6B6B"]
        /// Focus sessions on the calendar.
        static let focusColor = "#8B7CF6"
        /// Working hours shown when the calendar opens (Settings → Calendars).
        static let workStartKey = "calendar.workStart"
        static let workEndKey = "calendar.workEnd"
        static let workStart = 8
        static let workEnd = 21
    }

    enum Opacity {
        static let field: Double = 0.6
        static let chip: Double = 0.15
        static let pill: Double = 0.18
        static let card: Double = 0.5
        /// How much white a themed card mixes into the background: dark themes, light themes.
        static let cardLift: Double = 0.12
        static let cardLight: Double = 0.7
        static let chipFill: Double = 0.14
        /// Archived habits and countdowns.
        static let archived: Double = 0.7
        /// A partly logged day in the habit month map.
        static let partial: Double = 0.28
        /// Dims the window behind the search overlay.
        static let scrim: Double = 0.25
        /// Tint behind an Eisenhower quadrant.
        static let matrixFill: Double = 0.06
        /// Circle behind a habit's icon.
        static let iconFill: Double = 0.22
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

    /** Task list chrome (header, picker, groups) shared by the main list and the panels. */
    enum List {
        static let headerButton: CGFloat = 30
        static let pickerWidth: CGFloat = 260
        static let pickerHeight: CGFloat = 520
        static let pickerRow: CGFloat = 32
        static let pickerIcon: CGFloat = 20
        static let barWidth: CGFloat = 3
        static let foldAnimation: Animation = .snappy(duration: 0.28)
    }

    /** The new-task card. */
    enum Composer {
        static let width: CGFloat = 440
        static let bodyHeight: CGFloat = 150
        static let suggestionWidth: CGFloat = 240
        static let suggestionOffset: CGFloat = 34
        static let suggestionIndent: CGFloat = 80
        static let maxSuggestions = 8
    }

    /** Kanban and Timeline views. */
    enum Board {
        static let columnWidth: CGFloat = 280
        static let dayWidth: CGFloat = 40
        static let rowHeight: CGFloat = 30
        static let barHeight: CGFloat = 24
        /// Bars at least this many days long show their title inside.
        static let titleInsideDays = 4
    }

    /** Focus timer. */
    enum Focus {
        static let dial: CGFloat = 300
        static let ring: CGFloat = 10
        static let clock: CGFloat = 64
        static let button: CGFloat = 120
        static let historyWidth: CGFloat = 400
        static let headerBalance: CGFloat = 60
    }

    /** Search overlay. */
    enum Search {
        static let width: CGFloat = 560
        static let emptyHeight: CGFloat = 220
        static let resultsHeight: CGFloat = 420
        static let maxTasks = 50
        static let topInset: CGFloat = 90
    }

    /** Side panels (calendar beside the tasks, task list beside the calendar). */
    enum Panel {
        static let calendarWidth: CGFloat = 340
        static let tasksWidth: CGFloat = 320
        static let agendaDay: CGFloat = 34
        static let agendaTime: CGFloat = 48
        static let agendaDot: CGFloat = 9
        static let agendaBar: CGFloat = 3
    }

    /** Habits area (P6). */
    enum Habits {
        static let listMin: CGFloat = 440
        static let listIdeal: CGFloat = 460
        static let headerButton: CGFloat = 32
        static let rowIcon: CGFloat = 40
        static let heroIcon: CGFloat = 64
        static let dot: CGFloat = 22
        static let progressHeight: CGFloat = 6
        static let bigCheck: CGFloat = 52
        static let checkSymbol: CGFloat = 26
        static let checkInHeight: CGFloat = 60
        static let amountField: CGFloat = 80
        static let addButton: CGFloat = 56
        static let monthCell: CGFloat = 44
        static let monthDot: CGFloat = 36
        static let weekdayChip: CGFloat = 38
        static let sheetWidth: CGFloat = 440
        static let dotSpacing: CGFloat = 3
        static let detailWidth: CGFloat = 460
        static let cardMin: CGFloat = 250
        static let weekRing: CGFloat = 3
        static let weekRingSize: CGFloat = 26
        static let ring: CGFloat = 1.5
        static let ringStrong: CGFloat = 2
        static let progressRing: CGFloat = 3
        /// Trophy tint: amber, readable on light and dark (yellow isn't on white).
        static let gold = Color(red: 0.85, green: 0.47, blue: 0.02)
        /// Tint behind the top of the detail pane.
        static let wash: Double = 0.12
        static let gradient = LinearGradient(colors: [Color(red: 0.36, green: 0.37, blue: 0.91), Color(red: 0.55, green: 0.36, blue: 0.96)], startPoint: .leading, endPoint: .trailing)
    }

    /** Countdown area (P6). */
    enum Countdown {
        static let cardMin: CGFloat = 230
        static let cardHeight: CGFloat = 190
        static let icon: CGFloat = 36
        static let number: CGFloat = 44
        static let iconFill: Double = 0.22
        static let scrim: Double = 0.28
        static let shadow: Double = 0.3
        static let sheetWidth: CGFloat = 460
    }

    enum Size {
        static let heroIcon: CGFloat = 44
        static let summaryWidth: CGFloat = 420
        static let settingsWidth: CGFloat = 540
        static let settingsSidebar: CGFloat = 190
        static let settingsHeight: CGFloat = 560
        static let sidebarMin: CGFloat = 200
        static let sheetWidth: CGFloat = 380
        static let minRow: CGFloat = 40
        static let tagField: CGFloat = 90
        static let iconField: CGFloat = 44
        static let emojiCell: CGFloat = 32
        static let themeCard: CGFloat = 140
        static let themeSwatch: CGFloat = 44
        static let pickerWidth: CGFloat = 240
        static let pickerMaxHeight: CGFloat = 360
        static let filterWidth: CGFloat = 460
        static let filterFormHeight: CGFloat = 420
        static let swatch: CGFloat = 24
        static let listMin: CGFloat = 340
        static let detailMin: CGFloat = 320
    }
}

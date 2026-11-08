import SwiftUI

/// Main areas of the app, switched from the rail.
enum RailTab: Hashable, CaseIterable {
    case tasks, calendar, habits, countdown, matrix, focus, search

    var title: String {
        switch self {
        case .tasks: "Tasks"
        case .calendar: "Calendar"
        case .habits: "Habits"
        case .countdown: "Countdown"
        case .matrix: "Eisenhower Matrix"
        case .focus: "Focus"
        case .search: "Search"
        }
    }

    var symbol: String {
        switch self {
        case .tasks: "checkmark.square"
        case .calendar: "calendar"
        case .habits: "target"
        case .countdown: "hourglass"
        case .matrix: "square.grid.2x2"
        case .focus: "timer"
        case .search: "magnifyingglass"
        }
    }

    /// Areas built so far; the others show as dimmed until their phase ships.
    var isAvailable: Bool { true }

    var shortcut: KeyEquivalent {
        switch self {
        case .tasks: "1"
        case .calendar: "2"
        case .habits: "3"
        case .countdown: "4"
        case .matrix: "5"
        case .focus: "6"
        case .search: "k"
        }
    }
}

/// The icon rail on the window's left edge. It stays visible when the sidebar is collapsed, with
/// large targets (44 pt) so every area is one click away.
struct AppRail: View {
    @Environment(AppModel.self) private var app
    @Binding var tab: RailTab
    /// Search opens over the current area instead of replacing it.
    var search: () -> Void = {}

    var body: some View {
        VStack(spacing: Theme.Space.s) {
            ForEach(RailTab.allCases, id: \.self) { item in
                RailButton(symbol: item.symbol, title: item.isAvailable ? item.title : "\(item.title) (coming soon)", selected: tab == item) {
                    if item == .search { search() } else { tab = item }
                }
                .disabled(!item.isAvailable)
                .keyboardShortcut(item.shortcut, modifiers: .command)
            }
            Spacer(minLength: Theme.Space.l)
            RailButton(symbol: app.syncFailed ? "exclamationmark.arrow.triangle.2.circlepath" : "arrow.triangle.2.circlepath",
                       title: app.signedIn ? app.syncText : "Not signed in", selected: false) {
                app.syncNow()
            }
            .disabled(!app.signedIn)
            SettingsLink {
                Image(systemName: "gearshape")
                    .font(.system(size: Theme.Rail.icon, weight: .regular))
                    .frame(width: Theme.Rail.hit, height: Theme.Rail.hit)
                    .contentShape(RoundedRectangle(cornerRadius: Theme.Rail.radius, style: .continuous))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help("Settings")
        }
        .padding(.top, Theme.Rail.topInset)
        .padding(.bottom, Theme.Space.l)
        .frame(width: Theme.Rail.width)
        .frame(maxHeight: .infinity)
        .background(Color(nsColor: .underPageBackgroundColor))
    }
}

private struct RailButton: View {
    let symbol: String
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .symbolVariant(selected ? .fill : .none) // never build names like "calendar.fill" by hand: some don't exist
                .font(.system(size: Theme.Rail.icon, weight: .regular))
                .foregroundStyle(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .frame(width: Theme.Rail.hit, height: Theme.Rail.hit)
                .background(
                    RoundedRectangle(cornerRadius: Theme.Rail.radius, style: .continuous)
                        .fill(selected ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear))
                )
                .contentShape(RoundedRectangle(cornerRadius: Theme.Rail.radius, style: .continuous))
                .opacity(isEnabled ? 1 : 0.35)
        }
        .buttonStyle(.plain)
        .help(title)
        .accessibilityLabel(title)
    }
}

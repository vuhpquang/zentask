import SwiftUI

/// Main window until the task UI arrives (P3): what's in the local database and the sync state.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: Theme.Space.xl) {
            VStack(spacing: Theme.Space.s) {
                Image(systemName: "checkmark.circle")
                    .font(.system(size: Theme.Size.heroIcon, weight: .light))
                    .foregroundStyle(.tint)
                Text(AppInfo.displayVersion)
                    .font(.title2.weight(.medium))
            }

            Grid(horizontalSpacing: Theme.Space.m, verticalSpacing: Theme.Space.m) {
                GridRow {
                    CountTile(title: "Lists", value: model.counts.lists, symbol: "list.bullet")
                    CountTile(title: "Open", value: model.counts.openTasks, symbol: "circle")
                }
                GridRow {
                    CountTile(title: "Completed", value: model.counts.completedTasks, symbol: "checkmark.circle")
                    CountTile(title: "Trash", value: model.counts.trashedTasks, symbol: "trash")
                }
            }
            .frame(width: Theme.Size.summaryWidth)

            HStack(spacing: Theme.Space.s) {
                if model.syncFailed {
                    ProblemLabel(text: model.syncText)
                } else {
                    Label(model.syncText, systemImage: "arrow.triangle.2.circlepath").foregroundStyle(.secondary)
                }
                if !model.signedIn {
                    SettingsLink { Text("Sign in…") }
                }
            }
            .font(.callout)
        }
        .padding(Theme.Space.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct CountTile: View {
    let title: String
    let value: Int32
    let symbol: String

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                Text(value, format: .number)
                    .font(.title2.weight(.semibold))
                    .monospacedDigit()
                Text(title)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(Theme.Space.l)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))
    }
}

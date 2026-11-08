import SwiftUI

/// Settings → Appearance: light/dark and a colour theme.
struct AppearanceSettings: View {
    @AppStorage("appearance.mode") private var mode = AppearanceMode.system.rawValue
    @AppStorage("appearance.theme") private var themeId = "default"

    var body: some View {
        let theme = AppTheme.named(themeId)
        Form {
            Section {
                Picker("Appearance", selection: $mode) {
                    ForEach(AppearanceMode.allCases) { Text($0.title).tag($0.rawValue) }
                }
                .pickerStyle(.segmented)
                .disabled(theme.light == nil || theme.dark == nil)
            } footer: {
                if theme.light == nil || theme.dark == nil {
                    Text("\(theme.name) has only a \(theme.light == nil ? "dark" : "light") look.").foregroundStyle(.secondary)
                }
            }
            Section("Theme") {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.Size.themeCard), spacing: Theme.Space.m)], spacing: Theme.Space.m) {
                    ForEach(AppTheme.all) { t in
                        Button { themeId = t.id } label: { ThemeCard(theme: t, selected: t.id == themeId) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, Theme.Space.xs)
            }
        }
        .formStyle(.grouped)
    }
}

private struct ThemeCard: View {
    let theme: AppTheme
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.xs) {
                ForEach(Array([theme.light, theme.dark].compactMap { $0 }.enumerated()), id: \.offset) { _, c in
                    swatch(c)
                }
            }
            HStack {
                Text(theme.name).font(.callout.weight(.medium))
                Spacer()
                if selected { Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint) }
            }
        }
        .padding(Theme.Space.s)
        .background(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous).fill(.background.secondary))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous).strokeBorder(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary), lineWidth: selected ? 2 : 1))
        .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))
        .accessibilityLabel("\(theme.name) theme\(selected ? ", selected" : "")")
    }

    /// A tiny window: surface strip, background, accent dot.
    private func swatch(_ c: ThemeColors) -> some View {
        HStack(spacing: 0) {
            Rectangle().fill(c.surface ?? Color(nsColor: .underPageBackgroundColor)).frame(width: Theme.Space.l)
            ZStack {
                Rectangle().fill(c.background ?? Color(nsColor: .windowBackgroundColor))
                Circle().fill(c.accent).frame(width: Theme.Space.m, height: Theme.Space.m)
            }
        }
        .frame(height: Theme.Size.themeSwatch)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.tile - Theme.Space.xs, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.tile - Theme.Space.xs, style: .continuous).strokeBorder(.quaternary))
    }
}

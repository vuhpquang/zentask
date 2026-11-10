import AppKit
import SwiftUI

/// Light, dark, or follow the system (Settings → Appearance).
enum AppearanceMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
}

/// The colours a theme sets for one appearance. Anything nil keeps the system colour.
struct ThemeColors: Equatable {
    var accent: Color
    var background: Color?
    /// The rail and the sidebar.
    var surface: Color?
}

extension ThemeColors {
    /// Cards (kanban, habits, focus) sit on the background: a little lighter in dark, near white in light.
    /// nil = the theme keeps the system background, so cards keep the system colour too.
    func card(_ scheme: ColorScheme) -> Color? {
        background.map { scheme == .dark ? $0.mix(with: .white, by: Theme.Opacity.cardLift) : $0.mix(with: .white, by: Theme.Opacity.cardLight) }
    }
}

/// `.background` (the shape style cards fill with) is the system colour unless the environment sets one.
private struct CardBackground: ViewModifier {
    let card: Color?

    func body(content: Content) -> some View {
        if let card { content.backgroundStyle(card) } else { content }
    }
}

extension Color {
    /// The current theme's accent, resolved per appearance when drawn. Use it instead of `.accentColor`, which is the
    /// AccentColor asset (ZenTask's default indigo) whatever the theme.
    static let themeAccent = Color(nsColor: NSColor(name: "ZenTask.themeAccent") { appearance in
        let dark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let theme = AppTheme.named(UserDefaults.standard.string(forKey: "appearance.theme") ?? "default")
        return NSColor(theme.colors(dark ? .dark : .light).accent)
    })
}

/// A colour theme. ZenTask's own is Default; the others use well-known open palettes (Catppuccin, Dracula,
/// Nord, Solarized, Rosé Pine). A theme without a light (or dark) variant always shows its own.
struct AppTheme: Identifiable, Equatable {
    let id: String
    let name: String
    let light: ThemeColors?
    let dark: ThemeColors?

    static func hex(_ s: String) -> Color { Color(hex: s) ?? .indigo }

    static let all: [AppTheme] = [
        AppTheme(id: "default", name: "Default",
                 light: ThemeColors(accent: hex("#5B5FE9")), dark: ThemeColors(accent: hex("#8B8CFF"))),
        AppTheme(id: "catppuccin", name: "Catppuccin",
                 light: ThemeColors(accent: hex("#8839EF"), background: hex("#EFF1F5"), surface: hex("#E6E9EF")),
                 dark: ThemeColors(accent: hex("#CBA6F7"), background: hex("#1E1E2E"), surface: hex("#181825"))),
        // Light = Dracula's Alucard (frame #F5F5F5 / #E6E6E6, accent #644AC9), dark = Dracula.
        AppTheme(id: "dracula", name: "Dracula",
                 light: ThemeColors(accent: hex("#644AC9"), background: hex("#F5F5F5"), surface: hex("#E6E6E6")),
                 dark: ThemeColors(accent: hex("#BD93F9"), background: hex("#282A36"), surface: hex("#21222C"))),
        AppTheme(id: "nord", name: "Nord",
                 light: ThemeColors(accent: hex("#5E81AC"), background: hex("#ECEFF4"), surface: hex("#E5E9F0")),
                 dark: ThemeColors(accent: hex("#88C0D0"), background: hex("#2E3440"), surface: hex("#272C36"))),
        AppTheme(id: "solarized", name: "Solarized",
                 light: ThemeColors(accent: hex("#268BD2"), background: hex("#FDF6E3"), surface: hex("#EEE8D5")),
                 dark: ThemeColors(accent: hex("#2AA198"), background: hex("#002B36"), surface: hex("#073642"))),
        AppTheme(id: "rosepine", name: "Rosé Pine",
                 light: ThemeColors(accent: hex("#907AA9"), background: hex("#FAF4ED"), surface: hex("#F2E9E1")),
                 dark: ThemeColors(accent: hex("#C4A7E7"), background: hex("#191724"), surface: hex("#1F1D2E"))),
    ]

    static func named(_ id: String) -> AppTheme { all.first { $0.id == id } ?? all[0] }

    /// The scheme to force: the user's choice, or the theme's only variant; nil = follow the system.
    func forcedScheme(_ mode: AppearanceMode) -> ColorScheme? {
        if light == nil { return .dark }
        if dark == nil { return .light }
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    func colors(_ scheme: ColorScheme) -> ThemeColors {
        (scheme == .dark ? (dark ?? light) : (light ?? dark)) ?? ThemeColors(accent: AppTheme.all[0].light!.accent)
    }
}

extension EnvironmentValues {
    /// The active theme's colours (backgrounds nil = system).
    @Entry var themeColors = ThemeColors(accent: .themeAccent)
}

/// Applies Settings → Appearance to a window's content: forced light/dark, accent, background colours.
struct Themed: ViewModifier {
    @AppStorage("appearance.mode") private var mode = AppearanceMode.system.rawValue
    @AppStorage("appearance.theme") private var themeId = "default"

    func body(content: Content) -> some View {
        let theme = AppTheme.named(themeId)
        let forced = theme.forcedScheme(AppearanceMode(rawValue: mode) ?? .system)
        content
            .modifier(ThemeColorsApplier(theme: theme))
            .preferredColorScheme(forced)
            .onAppear { apply(forced) }
            .onChange(of: forced) { _, f in apply(f) }
    }

    /// Menus, popovers and sheets follow too.
    private func apply(_ scheme: ColorScheme?) {
        NSApp.appearance = scheme.map { NSAppearance(named: $0 == .dark ? .darkAqua : .aqua) } ?? nil
    }
}

private struct ThemeColorsApplier: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    let theme: AppTheme

    func body(content: Content) -> some View {
        let c = theme.colors(scheme)
        content
            .modifier(CardBackground(card: c.card(scheme)))
            .tint(c.accent)
            .environment(\.themeColors, c)
            .background(c.background ?? Color(nsColor: .windowBackgroundColor))
    }
}

extension View {
    /// Lists draw their own background; with a themed background they let it show through.
    func themedListBackground(_ colors: ThemeColors, surface: Bool = false) -> some View {
        let fill = surface ? colors.surface : colors.background
        return self.scrollContentBackground(fill == nil ? .automatic : .hidden)
            .background(fill ?? .clear)
    }
}

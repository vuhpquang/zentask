import SwiftUI
@preconcurrency import ZenTaskShared

// The pieces every task list shares (main list, calendar panel): the header with the list picker, the
// group/sort menu, the "…" menu, and collapsible group headers.

/// "☰ <icon> <name> ˅   ⇅  …": ☰ toggles the sidebar (when given), the name opens the list picker.
struct ListHeader<More: View>: View {
    @Environment(TasksModel.self) private var tasks
    @Binding var item: SidebarItem
    let options: ViewOptions
    let setOptions: (ViewOptions) -> Void
    var toggleSidebar: (() -> Void)?
    @ViewBuilder var more: () -> More
    @State private var picking = false

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            if let toggleSidebar {
                Button(action: toggleSidebar) {
                    Image(systemName: "sidebar.left").font(.title3).frame(width: Theme.List.headerButton, height: Theme.List.headerButton)
                }
                .buttonStyle(.borderless)
                .help("Show or hide the sidebar")
            }
            Button { picking.toggle() } label: {
                HStack(spacing: Theme.Space.s) {
                    ListGlyph(item: item)
                    Text(tasks.title(of: item)).font(.title2.weight(.bold)).lineLimit(1)
                    Image(systemName: "chevron.down").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Switch list")
            .popover(isPresented: $picking, arrowEdge: .bottom) {
                ListPicker(selection: Binding(get: { item }, set: { item = $0; picking = false }))
                    .environment(tasks)
            }
            Spacer(minLength: Theme.Space.s)
            SortMenu(item: item, options: options, setOptions: setOptions)
            Menu {
                Toggle("Hide completed", systemImage: "checkmark.circle", isOn: Binding(
                    get: { options.hideCompleted }, set: { setOptions(options.with(hideCompleted: $0)) }))
                Toggle("Hide details", systemImage: "text.alignleft", isOn: Binding(
                    get: { options.hideDetails }, set: { setOptions(options.with(hideDetails: $0)) }))
                more()
            } label: {
                Image(systemName: "ellipsis").font(.title3).frame(width: Theme.List.headerButton, height: Theme.List.headerButton)
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .help("More")
        }
    }
}

/// The list's icon: its emoji (lists named "🏠Personal" or a smart list's icon) or a symbol in its colour.
struct ListGlyph: View {
    @Environment(TasksModel.self) private var tasks
    let item: SidebarItem

    var body: some View {
        switch item {
        case .smart(let list):
            Image(systemName: TasksModel.symbol(list)).font(.title3).foregroundStyle(.tint)
        case .project(let id):
            let p = tasks.projects.first { $0.id == id }
            Image(systemName: "list.bullet").font(.title3).foregroundStyle(Color(hex: p?.color) ?? .themeAccent)
        case .tag(let name):
            Image(systemName: "number").font(.title3).foregroundStyle(Color(hex: tasks.tagColor(name)) ?? .themeAccent)
        case .custom(let id):
            if let icon = tasks.filters.first(where: { $0.id == id })?.icon, !icon.isEmpty {
                Text(icon).font(.title3)
            } else {
                Image(systemName: "line.3.horizontal.decrease.circle").font(.title3).foregroundStyle(.tint)
            }
        }
    }
}

/// Popover listing every place tasks can come from: smart lists, lists, smart filters, tags, with counts.
struct ListPicker: View {
    @Environment(TasksModel.self) private var tasks
    @Binding var selection: SidebarItem

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                ForEach([SmartList.all, .today, .tomorrow, .next7Days, .inbox], id: \.self) { list in
                    row(.smart(list)) { Image(systemName: TasksModel.symbol(list)).foregroundStyle(.tint) }
                }
                if !tasks.listProjects.isEmpty {
                    heading("Lists")
                    ForEach(tasks.listProjects, id: \.id) { p in
                        row(.project(p.id), dot: Color(hex: p.color)) { Image(systemName: "list.bullet").foregroundStyle(Color(hex: p.color) ?? .secondary) }
                    }
                }
                if !tasks.filters.isEmpty {
                    heading("Smart lists")
                    ForEach(tasks.filters, id: \.id) { f in
                        row(.custom(f.id)) {
                            if let icon = f.icon, !icon.isEmpty { Text(icon) } else { Image(systemName: "line.3.horizontal.decrease.circle").foregroundStyle(.secondary) }
                        }
                    }
                }
                if !tasks.tags.isEmpty {
                    heading("Tags")
                    ForEach(tasks.tags, id: \.id) { t in
                        row(.tag(t.name)) { Image(systemName: "number").foregroundStyle(Color(hex: t.color) ?? .secondary) }
                    }
                }
            }
            .padding(Theme.Space.s)
        }
        .frame(width: Theme.List.pickerWidth)
        .frame(maxHeight: Theme.List.pickerHeight)
    }

    private func heading(_ title: String) -> some View {
        Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            .padding(.horizontal, Theme.Space.s).padding(.top, Theme.Space.m).padding(.bottom, Theme.Space.xxs)
    }

    private func row(_ item: SidebarItem, dot: Color? = nil, @ViewBuilder icon: () -> some View) -> some View {
        let selected = item == selection
        let count = tasks.count(item)
        return Button { selection = item } label: {
            HStack(spacing: Theme.Space.m) {
                icon().frame(width: Theme.List.pickerIcon)
                Text(tasks.title(of: item)).lineLimit(1)
                Spacer(minLength: Theme.Space.s)
                if let dot { Circle().fill(dot).frame(width: Theme.Calendar.dot, height: Theme.Calendar.dot) }
                if count > 0 { Text("\(count)").font(.callout.monospacedDigit()).foregroundStyle(.secondary) }
            }
            .padding(.horizontal, Theme.Space.s)
            .frame(height: Theme.List.pickerRow)
            .background(RoundedRectangle(cornerRadius: Theme.Radius.tile - 2, style: .continuous).fill(selected ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// "⇅": Group by › / Sort by › / Order ›, each showing its current choice.
struct SortMenu: View {
    let item: SidebarItem
    let options: ViewOptions
    let setOptions: (ViewOptions) -> Void

    private var groups: [(String, String)] {
        var g = [("", "Default"), ("list", "List"), ("date", "Date"), ("priority", "Priority"), ("tag", "Tag")]
        if case .project = item { g.append(("column", "Section")) }
        g.append(("none", "None"))
        return g
    }

    private let sorts = [("", "Default"), ("date", "Date"), ("priority", "Priority"), ("title", "Title"), ("manual", "Manual")]

    private var orders: [(String, String)] {
        let listDefault: Bool = { if case .project = item { return options.sort.isEmpty } else { return false } }()
        return switch listDefault ? "manual" : options.sort {
        case "priority": [("", "Highest first"), ("desc", "Lowest first")]
        case "date", "": [("", "Earliest first"), ("desc", "Latest first")]
        case "title": [("", "A to Z"), ("desc", "Z to A")]
        default: [("", "Normal"), ("desc", "Reversed")]
        }
    }

    var body: some View {
        Menu {
            choice("Group by", symbol: "rectangle.3.group", options.group, groups) { setOptions(options.with(group: $0)) }
            choice("Sort by", symbol: "line.3.horizontal.decrease", options.sort, sorts) { setOptions(options.with(sort: $0)) }
            choice("Order", symbol: "arrow.up.arrow.down", options.order, orders) { setOptions(options.with(order: $0)) }
        } label: {
            Image(systemName: "arrow.up.arrow.down").font(.title3).frame(width: Theme.List.headerButton, height: Theme.List.headerButton)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Group and sort")
    }

    private func choice(_ title: String, symbol: String, _ current: String, _ all: [(String, String)], set: @escaping (String) -> Void) -> some View {
        Menu {
            ForEach(all, id: \.0) { value, label in
                Button { set(value) } label: {
                    if value == current { Label(label, systemImage: "checkmark") } else { Text(label) }
                }
            }
        } label: {
            Label(title, systemImage: symbol)
            Text(all.first { $0.0 == current }?.1 ?? "Default")
        }
    }
}

/// A group's header row: rotating chevron, title, count. Click (anywhere on it) to fold or unfold.
struct GroupHeaderRow: View {
    let title: String
    let count: Int
    let collapsed: Bool
    var color: Color?
    let toggle: () -> Void

    var body: some View {
        Button {
            withMotion { toggle() }
        } label: {
            HStack(spacing: Theme.Space.s) {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(collapsed ? 0 : 90))
                    .frame(width: Theme.Space.m)
                Text(title).font(.headline)
                Text("\(count)").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.vertical, Theme.Space.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title), \(count) tasks, \(collapsed ? "collapsed" : "expanded")")
        .accessibilityAddTraits(.isHeader)
    }
}

/// The list's colour as a bar down the leading edge of its rows (when grouped by list).
struct GroupBar: View {
    let color: Color

    var body: some View {
        HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: Theme.List.barWidth / 2, style: .continuous)
                .fill(color)
                .frame(width: Theme.List.barWidth)
            Spacer(minLength: 0)
        }
    }
}

/// A task row's background: the group colour bar and, when selected, the theme's highlight. The List's own
/// selection is switched off (`themedSelection()`): AppKit draws it in the AccentColor asset, never the theme.
struct RowBackground: View {
    @Environment(\.themeColors) private var themeColors
    let selected: Bool
    let groupColor: Color?

    var body: some View {
        ZStack {
            if selected {
                RoundedRectangle(cornerRadius: Theme.Radius.sidebarRow, style: .continuous)
                    .fill(themeColors.accent.opacity(Theme.Opacity.chip))
                    .padding(.horizontal, Theme.Space.xs)
            }
            if let groupColor { GroupBar(color: groupColor) }
        }
    }
}

extension View {
    /// Keeps the List's selection (keyboard, focus, multi-row logic) but stops AppKit drawing it; rows draw
    /// `RowBackground` instead.
    func themedSelection() -> some View { background(SelectionHighlightOff()) }
}

/// Finds the table behind the SwiftUI List it sits beside and turns off its selection drawing.
private struct SelectionHighlightOff: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { Finder() }
    func updateNSView(_ view: NSView, context: Context) { (view as? Finder)?.apply() }

    final class Finder: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            DispatchQueue.main.async { [weak self] in self?.apply() }
        }

        func apply() {
            // Walk up until an ancestor holds exactly one table: that is this List's.
            var node = superview
            while let n = node {
                let tables = Self.tables(in: n)
                if tables.count == 1 {
                    if tables[0].selectionHighlightStyle != .none { tables[0].selectionHighlightStyle = .none }
                    return
                }
                if tables.count > 1 { return }
                node = n.superview
            }
        }

        private static func tables(in view: NSView) -> [NSTableView] {
            var out: [NSTableView] = []
            for sub in view.subviews {
                if let t = sub as? NSTableView { out.append(t) } else { out += tables(in: sub) }
            }
            return out
        }
    }
}

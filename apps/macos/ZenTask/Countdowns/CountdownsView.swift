import SwiftUI
@preconcurrency import ZenTaskShared

/// The Countdown area (rail ⌘4): colourful cards counting the days to (or since) a date.
struct CountdownsView: View {
    @Environment(CountdownsModel.self) private var countdowns

    var body: some View {
        @Bindable var countdowns = countdowns
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("Countdown").font(.title2.weight(.bold))
                Spacer()
                Button { countdowns.sheet = .new } label: {
                    Label("New countdown", systemImage: "plus")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .padding(.horizontal, Theme.Space.xl)
            .padding(.top, Theme.Space.l)
            .padding(.bottom, Theme.Space.s)
            Picker("Show", selection: $countdowns.typeFilter) {
                Text("All").tag("")
                ForEach(CountdownDraft.types, id: \.id) { t in Text(t.title).tag(t.id) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.Space.xl)
            .padding(.bottom, Theme.Space.m)
            if countdowns.cards.isEmpty {
                ContentUnavailableView {
                    Label("No countdowns yet", systemImage: "hourglass")
                } description: {
                    Text("Count down to a trip, or keep birthdays and anniversaries in view")
                } actions: {
                    Button("New countdown") { countdowns.sheet = .new }
                }
                .frame(maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.Space.l) {
                        if countdowns.active.isEmpty && countdowns.archived.isEmpty {
                            Text("Nothing of this type yet").foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.top, Theme.Space.xxl)
                        }
                        grid(countdowns.active)
                        if !countdowns.archived.isEmpty {
                            DisclosureGroup("Archived (\(countdowns.archived.count))", isExpanded: $countdowns.showArchived) {
                                grid(countdowns.archived).opacity(Theme.Opacity.archived).padding(.top, Theme.Space.s)
                            }
                            .font(.headline)
                        }
                    }
                    .padding(.horizontal, Theme.Space.xl)
                    .padding(.bottom, Theme.Space.xl)
                }
            }
        }
        .onAppear { countdowns.start() }
        .sheet(item: $countdowns.sheet) { sheet in
            CountdownEditor(editing: sheet).environment(countdowns)
        }
        .confirmationDialog(
            "Delete \"\(countdowns.confirmDelete?.name ?? "")\"?",
            isPresented: Binding(get: { countdowns.confirmDelete != nil }, set: { if !$0 { countdowns.confirmDelete = nil } })
        ) {
            Button("Delete", role: .destructive) {
                if let c = countdowns.confirmDelete { countdowns.delete(c) }
                countdowns.confirmDelete = nil
            }
            Button("Cancel", role: .cancel) { countdowns.confirmDelete = nil }
        } message: {
            Text("The countdown is deleted on every device. Archive it instead to keep it.")
        }
    }

    private func grid(_ cards: [CountdownCard]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.Countdown.cardMin), spacing: Theme.Space.l)], spacing: Theme.Space.l) {
            ForEach(cards, id: \.countdown.id) { card in
                CountdownCardView(card: card)
            }
        }
    }
}

private struct CountdownCardView: View {
    @Environment(CountdownsModel.self) private var countdowns
    let card: CountdownCard

    var body: some View {
        let c = card.countdown
        let date = Date(timeIntervalSince1970: Double(card.dateMillis) / 1000)
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(alignment: .top) {
                ZStack {
                    Circle().fill(.white.opacity(Theme.Countdown.iconFill))
                    if let emoji = HabitLook.emoji(c.icon) {
                        Text(emoji).font(.title3)
                    } else {
                        Image(systemName: CountdownLook.symbol(c.type)).font(.title3.weight(.semibold))
                    }
                }
                .frame(width: Theme.Countdown.icon, height: Theme.Countdown.icon)
                Spacer()
                if let milestone = CountdownLook.milestone(card) {
                    Text(milestone)
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, Theme.Space.s)
                        .padding(.vertical, Theme.Space.xxs)
                        .background(Capsule().fill(.white.opacity(Theme.Countdown.iconFill)))
                }
            }
            Text(c.name).font(.title3.weight(.semibold)).lineLimit(2)
            Spacer(minLength: 0)
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
                if card.daysLeft != 0 {
                    Text("\(abs(card.daysLeft))")
                        .font(.system(size: Theme.Countdown.number, weight: .bold, design: .rounded).monospacedDigit())
                }
                Text(CountdownLook.caption(card.daysLeft))
                    .font(card.daysLeft == 0 ? .system(size: Theme.Countdown.number, weight: .bold, design: .rounded) : .headline)
            }
            HStack(spacing: Theme.Space.xs) {
                Text(date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).year()))
                if c.`repeat` == "yearly" { Image(systemName: "repeat").accessibilityLabel("Every year") }
            }
            .font(.callout)
        }
        .foregroundStyle(.white)
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, minHeight: Theme.Countdown.cardHeight, alignment: .leading)
        .background {
            let color = CountdownLook.color(c)
            RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                .fill(color.gradient)
                // Darkens the lower half so white text stays readable on light colours (amber, pink).
                .overlay(
                    LinearGradient(colors: [.clear, .black.opacity(Theme.Countdown.scrim)], startPoint: .top, endPoint: .bottom)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
                )
                .shadow(color: color.opacity(Theme.Countdown.shadow), radius: Theme.Space.s, y: Theme.Space.xs)
        }
        .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .onTapGesture(count: 2) { countdowns.sheet = .edit(c.id) }
        .help(c.note.isEmpty ? "Double-click to edit" : c.note)
        .contextMenu {
            Button("Edit…", systemImage: "pencil") { countdowns.sheet = .edit(c.id) }
            Button(c.archivedAt == nil ? "Archive" : "Unarchive", systemImage: c.archivedAt == nil ? "archivebox" : "tray.and.arrow.up") {
                countdowns.archive(c)
            }
            Divider()
            Button("Delete…", systemImage: "trash", role: .destructive) { countdowns.confirmDelete = c }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Create or edit a countdown.
struct CountdownEditor: View {
    @Environment(CountdownsModel.self) private var countdowns
    @Environment(\.dismiss) private var dismiss
    let editing: CountdownSheet
    @State private var draft = CountdownDraft()
    @State private var original: Countdown?

    private var editingId: String? { if case .edit(let id) = editing { id } else { nil } }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(editingId == nil ? "New countdown" : "Edit countdown").font(.title3.weight(.semibold))
            HStack(spacing: Theme.Space.s) {
                TextField("🎉", text: $draft.icon)
                    .textFieldStyle(.roundedBorder)
                    .multilineTextAlignment(.center)
                    .frame(width: Theme.Size.iconField)
                    .help("An emoji for the card (optional)")
                TextField("Name, e.g. Trip to Đà Lạt", text: $draft.name).textFieldStyle(.roundedBorder)
            }
            .controlSize(.large)
            Picker("Type", selection: $draft.type) {
                ForEach(CountdownDraft.types, id: \.id) { t in Label(t.title, systemImage: t.symbol).tag(t.id) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .onChange(of: draft.type) { _, t in
                if t == "BIRTHDAY" || t == "ANNIVERSARY" { draft.yearly = true }
            }
            DatePicker("Date", selection: $draft.date, displayedComponents: .date)
            Toggle("Repeat every year", isOn: $draft.yearly)
            Toggle("Show in calendar", isOn: $draft.showInCalendar)
            HStack(spacing: Theme.Space.s) {
                ForEach(Palette.colors, id: \.self) { hex in
                    Button { draft.color = hex } label: {
                        Circle()
                            .fill(Color(hex: hex) ?? .secondary)
                            .frame(width: Theme.Size.swatch, height: Theme.Size.swatch)
                            .overlay(Circle().strokeBorder(.primary, lineWidth: draft.color == hex ? 2 : 0))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Colour \(hex)")
                }
            }
            TextField("Note", text: $draft.note, axis: .vertical).textFieldStyle(.roundedBorder).lineLimit(2...4)
            HStack {
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(editingId == nil ? "Create" : "Save") {
                    countdowns.save(draft, original: original)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!draft.isValid)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.Countdown.sheetWidth)
        .onAppear {
            if let id = editingId, let c = countdowns.cards.first(where: { $0.countdown.id == id })?.countdown {
                original = c
                draft = CountdownDraft(c, calendar: countdowns.calendar)
            }
        }
    }
}

enum CountdownLook {
    static func symbol(_ type: String) -> String { CountdownDraft.types.first { $0.id == type }?.symbol ?? "hourglass" }

    static func color(_ c: Countdown) -> Color {
        if let color = Color(hex: c.color) { return color }
        let i = abs(c.id.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }) % Palette.colors.count
        return Color(hex: Palette.colors[i]) ?? .accentColor
    }

    /// "days left", "days ago" or "Today".
    static func caption(_ days: Int32) -> String {
        switch days {
        case 0: "Today"
        case 1: "day left"
        case -1: "day ago"
        case ..<0: "days ago"
        default: "days left"
        }
    }

    /// Age / anniversary count for yearly ones ("turns 66", "10th anniversary").
    static func milestone(_ card: CountdownCard) -> String? {
        guard card.years > 0 else { return nil }
        switch card.countdown.type {
        case "BIRTHDAY": return "turns \(card.years)"
        case "ANNIVERSARY": return "\(card.years.formatted(.number)) years"
        default: return nil
        }
    }
}

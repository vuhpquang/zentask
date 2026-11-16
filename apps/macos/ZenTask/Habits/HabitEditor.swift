import SwiftUI
@preconcurrency import ZenTaskShared

/// Create or edit a habit: name, emoji, colour, how often, yes/no or an amount with a goal.
struct HabitEditor: View {
    @Environment(HabitsModel.self) private var habits
    @Environment(\.dismiss) private var dismiss
    let editing: HabitSheet
    @State private var draft = HabitDraft()
    @State private var kind = 0
    @State private var original: Habit?
    @State private var newReminder = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: .now) ?? .now
    @State private var places: [Place] = []
    @State private var managingPlaces = false
    /// Passed in by the habits area so the editor can open Manage places (the sheet lives on TasksModel's core).
    var tasks: TasksModel?

    private var editingId: String? { if case .edit(let id) = editing { id } else { nil } }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(editingId == nil ? "New habit" : "Edit habit").font(.title3.weight(.semibold))
            HStack(spacing: Theme.Space.s) {
                EmojiPicker(emoji: $draft.icon, placeholder: "target")
                TextField("Name, e.g. Read 20 pages", text: $draft.name)
                    .textFieldStyle(.roundedBorder)
            }
            .controlSize(.large)
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

            VStack(alignment: .leading, spacing: Theme.Space.s) {
                Text("How often").font(.headline)
                Picker("How often", selection: $kind) {
                    Text("Every day").tag(0)
                    Text("Some days").tag(1)
                    Text("Times a week").tag(2)
                    Text("Times a month").tag(3)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .onChange(of: kind) { _, k in
                    switch k {
                    case 1: if case .weekdays = draft.frequency {} else { draft.frequency = .weekdays([2, 3, 4, 5, 6]) }
                    case 2: if case .timesAWeek = draft.frequency {} else { draft.frequency = .timesAWeek(3); draft.remindFrom = nil }
                    case 3: if case .timesAMonth = draft.frequency {} else { draft.frequency = .timesAMonth(1); draft.remindFrom = nil }
                    default: draft.frequency = .daily
                    }
                }
                switch draft.frequency {
                case .weekdays(let set):
                    HStack(spacing: Theme.Space.s) {
                        ForEach([2, 3, 4, 5, 6, 7, 1], id: \.self) { d in
                            let on = set.contains(d)
                            Button {
                                var next = set
                                if on { next.remove(d) } else { next.insert(d) }
                                draft.frequency = .weekdays(next)
                            } label: {
                                Text(habits.calendar.shortWeekdaySymbols[d - 1])
                                    .font(.callout.weight(.medium))
                                    .frame(width: Theme.Habits.weekdayChip, height: Theme.Habits.weekdayChip)
                                    .foregroundStyle(on ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                                    .background(Circle().fill(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary)))
                                    .contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                case .timesAWeek(let n):
                    Stepper("\(n) times a week", value: Binding(get: { n }, set: { draft.frequency = .timesAWeek($0) }), in: 1...7)
                case .timesAMonth(let n):
                    Stepper("\(n) times a month", value: Binding(get: { n }, set: { draft.frequency = .timesAMonth($0) }), in: 1...31)
                case .daily:
                    EmptyView()
                }
            }

            VStack(alignment: .leading, spacing: Theme.Space.s) {
                Text("Goal").font(.headline)
                Picker("Goal", selection: $draft.isNumber) {
                    Text("Done or not").tag(false)
                    Text("An amount").tag(true)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                if draft.isNumber {
                    Grid(alignment: .leading, horizontalSpacing: Theme.Space.m, verticalSpacing: Theme.Space.s) {
                        GridRow {
                            Text("Daily goal").foregroundStyle(.secondary)
                            TextField("8", value: $draft.goal, format: .number).textFieldStyle(.roundedBorder).frame(width: Theme.Size.tagField)
                            TextField("Unit, e.g. cups", text: $draft.unit).textFieldStyle(.roundedBorder)
                        }
                        GridRow {
                            Text("Each click adds").foregroundStyle(.secondary)
                            TextField("1", value: $draft.step, format: .number).textFieldStyle(.roundedBorder).frame(width: Theme.Size.tagField)
                        }
                    }
                }
            }

            VStack(alignment: .leading, spacing: Theme.Space.s) {
                Text("Reminders").font(.headline)
                if !draft.reminders.isEmpty {
                    FlowChips(items: draft.reminders) { time in
                        Button {
                            draft.reminders.removeAll { $0 == time }
                        } label: {
                            Label(time, systemImage: "xmark.circle.fill").labelStyle(.titleAndIcon)
                        }
                        .help("Remove this reminder")
                    }
                }
                HStack(spacing: Theme.Space.s) {
                    DatePicker("New reminder", selection: $newReminder, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                    Button("Add time", systemImage: "bell.badge") {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: newReminder)
                        let time = String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
                        if !draft.reminders.contains(time) { draft.reminders = (draft.reminders + [time]).sorted() }
                    }
                }
                if draft.frequency.isCounted {
                    HStack(spacing: Theme.Space.s) {
                        Text("Start reminding").foregroundStyle(.secondary)
                        Picker("Start reminding", selection: $draft.remindFrom) { remindFromOptions }
                            .labelsHidden()
                            .fixedSize()
                    }
                }
                HStack(spacing: Theme.Space.s) {
                    Text("Location").foregroundStyle(.secondary)
                    Picker("Location", selection: $draft.placeId) {
                        Text("None").tag(String?.none)
                        if !places.isEmpty { Divider() }
                        ForEach(places, id: \.id) { p in Text(p.name).tag(String?.some(p.id)) }
                    }
                    .labelsHidden()
                    .fixedSize()
                    if draft.placeId != nil {
                        Picker("When", selection: $draft.placeTrigger) {
                            Text("Arrive").tag(Place.companion.ARRIVE)
                            Text("Leave").tag(Place.companion.LEAVE)
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .fixedSize()
                    }
                    Spacer(minLength: 0)
                    if let tasks {
                        Button("Manage places…") { managingPlaces = true }
                            .buttonStyle(.link)
                            .fixedSize()
                            .sheet(isPresented: $managingPlaces, onDismiss: reloadPlaces) { PlacesSheet().environment(tasks) }
                    }
                }
                Text(reminderHint).font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(editingId == nil ? "Create" : "Save") {
                    habits.save(draft, original: original)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!draft.isValid)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .frame(width: Theme.Habits.sheetWidth)
        .onAppear {
            reloadPlaces()
            if let id = editingId, let h = habits.rows.first(where: { $0.habit.id == id })?.habit {
                original = h
                draft = HabitDraft(habit: h)
            }
            kind = switch draft.frequency { case .daily: 0; case .weekdays: 1; case .timesAWeek: 2; case .timesAMonth: 3 }
        }
    }

    /// Full names, Monday first (the habits calendar has no locale, so its symbols come out short).
    private static let weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    private func reloadPlaces() {
        places = habits.core.placeActions.list()
        if let id = draft.placeId, !places.contains(where: { $0.id == id }) { draft.placeId = nil }
    }

    /// Weekly: Every day or from Monday … Sunday. Monthly: Every day, from day 1–31, or from the month's first Monday … Sunday.
    @ViewBuilder private var remindFromOptions: some View {
        Text("Every day").tag(Int?.none)
        Divider()
        // ISO weekdays 1 = Monday … 7 = Sunday.
        if case .timesAMonth = draft.frequency {
            ForEach(1...7, id: \.self) { d in
                Text("From the first " + Self.weekdays[d - 1]).tag(Int?.some(Int(Habit.companion.REMIND_FIRST_WEEKDAY) + d))
            }
            Divider()
            ForEach(1...31, id: \.self) { d in Text("From day \(d)").tag(Int?.some(d)) }
        } else {
            ForEach(1...7, id: \.self) { d in Text("From " + Self.weekdays[d - 1]).tag(Int?.some(d)) }
        }
    }

    private var reminderHint: String {
        var parts: [String] = []
        parts.append(draft.frequency.isCounted
                     ? "Times remind from the start day, every day until the target is met"
                     : "Times remind on days the habit is due and not done yet")
        if draft.placeId != nil { parts.append("the place reminds on Wi‑Fi at that place (Mac) or by GPS and Wi‑Fi (phone)") }
        else if places.isEmpty { parts.append("add a place to be reminded when you arrive or leave") }
        return parts.joined(separator: "; ")
    }
}

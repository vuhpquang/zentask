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
                HStack(spacing: Theme.Space.s) {
                    ForEach(draft.reminders, id: \.self) { time in
                        Button {
                            draft.reminders.removeAll { $0 == time }
                        } label: {
                            Label(time, systemImage: "xmark.circle.fill").labelStyle(.titleAndIcon)
                        }
                        .help("Remove this reminder")
                    }
                    DatePicker("New reminder", selection: $newReminder, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                    Button("Add", systemImage: "bell.badge") {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: newReminder)
                        let time = String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
                        if !draft.reminders.contains(time) { draft.reminders = (draft.reminders + [time]).sorted() }
                    }
                }
                if draft.frequency.isCounted {
                    Picker("Start reminding", selection: $draft.remindFrom) {
                        Text("Every day").tag(Int?.none)
                        if case .timesAMonth = draft.frequency {
                            ForEach(1...31, id: \.self) { d in Text("From day \(d)").tag(Int?.some(d)) }
                        } else {
                            // ISO weekdays 1 = Monday … 7 = Sunday; the symbols array starts on Sunday.
                            ForEach(1...7, id: \.self) { d in Text("From " + habits.calendar.weekdaySymbols[d % 7]).tag(Int?.some(d)) }
                        }
                    }
                    .fixedSize()
                }
                Text(draft.frequency.isCounted
                     ? "From the start day, every day until the target is met"
                     : "On days the habit is due and not done yet").font(.callout).foregroundStyle(.secondary)
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
            if let id = editingId, let h = habits.rows.first(where: { $0.habit.id == id })?.habit {
                original = h
                draft = HabitDraft(habit: h)
            }
            kind = switch draft.frequency { case .daily: 0; case .weekdays: 1; case .timesAWeek: 2; case .timesAMonth: 3 }
        }
    }
}

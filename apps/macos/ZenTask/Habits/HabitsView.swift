import SwiftUI
@preconcurrency import ZenTaskShared

/// The Habits area (rail ⌘3): the week's progress rings, habits by section as cards (or rows), and the
/// selected habit's check-in, stats and month map on the right.
struct HabitsView: View {
    @Environment(HabitsModel.self) private var habits

    var body: some View {
        HStack(spacing: 0) {
            HabitBoardPane()
                .frame(minWidth: Theme.Habits.listMin, maxWidth: .infinity)
            Divider()
            Group {
                if let row = habits.selected {
                    HabitDetail(row: row)
                } else {
                    ContentUnavailableView {
                        Label("No habit selected", systemImage: "target")
                    } description: {
                        Text("Pick a habit, or create one to start a streak")
                    } actions: {
                        Button("New habit") { habits.sheet = .new }.buttonStyle(.borderedProminent)
                    }
                }
            }
            .frame(width: Theme.Habits.detailWidth)
        }
        .onAppear { habits.start(); habits.setVisible(true) }
        .onDisappear { habits.setVisible(false) }
        .sheet(item: Binding(get: { habits.sheet }, set: { habits.sheet = $0 })) { sheet in
            HabitEditor(editing: sheet).environment(habits)
        }
        .confirmationDialog(
            "Delete the habit \"\(habits.confirmDelete?.name ?? "")\"?",
            isPresented: Binding(get: { habits.confirmDelete != nil }, set: { if !$0 { habits.confirmDelete = nil } })
        ) {
            Button("Delete", role: .destructive) {
                if let h = habits.confirmDelete { habits.delete(h) }
                habits.confirmDelete = nil
            }
            Button("Cancel", role: .cancel) { habits.confirmDelete = nil }
        } message: {
            Text("The habit and all its check-ins are deleted on every device. Archive it instead to keep its history.")
        }
    }
}

// MARK: - Board

private struct HabitBoardPane: View {
    @Environment(HabitsModel.self) private var habits
    @AppStorage("habits.grid") private var grid = true

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
                Text("Habits").font(.title2.weight(.bold))
                Spacer()
                Button { grid.toggle() } label: {
                    Image(systemName: grid ? "list.bullet" : "square.grid.2x2").font(.title3)
                        .frame(width: Theme.Habits.headerButton, height: Theme.Habits.headerButton)
                }
                .buttonStyle(.borderless)
                .help(grid ? "Show as a list" : "Show as cards")
                Button { habits.sheet = .new } label: {
                    Image(systemName: "plus").font(.title3).frame(width: Theme.Habits.headerButton, height: Theme.Habits.headerButton)
                }
                .buttonStyle(.borderless)
                .help("New habit")
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.top, Theme.Space.m)
            WeekRings()
                .padding(.horizontal, Theme.Space.l)
                .padding(.vertical, Theme.Space.m)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Theme.Space.s) {
                    ForEach(habits.groups, id: \.key) { group in
                        if let title = group.title {
                            GroupHeaderRow(title: title, count: group.rows.count, collapsed: habits.isFolded(group.key)) {
                                habits.toggleFold(group.key)
                            }
                            .padding(.horizontal, Theme.Space.s)
                        }
                        if !habits.isFolded(group.key) {
                            if grid {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.Habits.cardMin), spacing: Theme.Space.m)], spacing: Theme.Space.m) {
                                    ForEach(group.rows, id: \.habit.id) { HabitCard(row: $0) }
                                }
                            } else {
                                ForEach(group.rows, id: \.habit.id) { HabitRowView(row: $0) }
                            }
                        }
                    }
                    if !habits.archived.isEmpty {
                        GroupHeaderRow(title: "Archived", count: habits.archived.count, collapsed: !habits.showArchived) { habits.showArchived.toggle() }
                            .padding(.horizontal, Theme.Space.s)
                            .padding(.top, Theme.Space.m)
                        if habits.showArchived {
                            ForEach(habits.archived, id: \.habit.id) { HabitRowView(row: $0).opacity(Theme.Opacity.archived) }
                        }
                    }
                }
                .padding(.horizontal, Theme.Space.l)
                .padding(.bottom, Theme.Space.l)
            }
            .overlay {
                if habits.rows.isEmpty {
                    ContentUnavailableView("No habits yet", systemImage: "target", description: Text("Click + to add your first habit"))
                }
            }
            .focusable()
            .focusEffectDisabled()
            .onKeyPress(.downArrow) { habits.moveSelection(1); return .handled }
            .onKeyPress(.upArrow) { habits.moveSelection(-1); return .handled }
        }
    }
}

/// Monday to Sunday: each day's share of due habits done, as a ring; today stands out.
private struct WeekRings: View {
    @Environment(HabitsModel.self) private var habits

    var body: some View {
        HStack(spacing: 0) {
            ForEach(habits.week, id: \.stamp) { day in
                let date = habits.date(day.stamp)
                let isToday = day.stamp == habits.today
                let progress = day.due > 0 ? Double(day.done) / Double(day.due) : 0
                VStack(spacing: Theme.Space.xxs) {
                    Text(date.formatted(.dateTime.weekday(.abbreviated))).font(.caption).foregroundStyle(.secondary)
                    Text("\(habits.calendar.component(.day, from: date))")
                        .font(.callout.weight(isToday ? .bold : .medium).monospacedDigit())
                        .foregroundStyle(isToday ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    ZStack {
                        Circle().stroke(.quaternary, lineWidth: Theme.Habits.weekRing)
                        Circle().trim(from: 0, to: progress)
                            .stroke(Theme.Habits.gradient, style: StrokeStyle(lineWidth: Theme.Habits.weekRing, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        if day.due > 0 && day.done == day.due {
                            Image(systemName: "checkmark").font(.caption2.weight(.bold)).foregroundStyle(.tint)
                        }
                    }
                    .frame(width: Theme.Habits.weekRingSize, height: Theme.Habits.weekRingSize)
                    .animation(Motion.pop, value: progress)
                }
                .frame(maxWidth: .infinity)
                .help(day.due == 0 ? "No habits due" : "\(day.done) of \(day.due) done")
                .opacity(day.stamp > habits.today ? Theme.Opacity.archived : 1)
            }
        }
    }
}

/// A habit card: icon, name, total days and streak, then this week's seven days to click.
private struct HabitCard: View {
    @Environment(HabitsModel.self) private var habits
    let row: HabitRow

    var body: some View {
        let selected = habits.selection == row.habit.id
        let color = HabitLook.color(row.habit)
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            HStack(spacing: Theme.Space.m) {
                HabitIcon(habit: row.habit, size: Theme.Habits.rowIcon)
                VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                    Text(row.habit.name).font(.body.weight(.semibold)).lineLimit(1)
                    HStack(spacing: Theme.Space.s) {
                        Label { Text("\(row.stats.totalDays) days") } icon: { Image(systemName: "bolt.fill").foregroundStyle(.blue) }
                        Label { Text("\(row.stats.currentStreak) \(row.stats.unit)") } icon: { Image(systemName: "flame.fill").foregroundStyle(.orange) }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .labelStyle(.titleAndIcon)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 0) {
                ForEach(habits.weekDays(), id: \.self) { day in
                    let stamp = habits.stamp(day)
                    Button { habits.tap(row, on: day) } label: {
                        CheckCircle(done: habits.isDone(row, day), progress: progress(day), color: color, size: Theme.Habits.dot, emphasised: stamp == habits.today)
                    }
                    .buttonStyle(.plain)
                    .disabled(habits.isFuture(day))
                    .opacity(habits.isFuture(day) ? Theme.Opacity.archived : 1)
                    .frame(maxWidth: .infinity)
                    .help(day.formatted(.dateTime.weekday(.wide).day().month()))
                }
            }
        }
        .padding(Theme.Detail.cardPadding)
        .background(
            RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous)
                .fill(selected ? AnyShapeStyle(color.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.background.secondary))
        )
        .overlay(
            RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous)
                .strokeBorder(selected ? color : .clear, lineWidth: Theme.Habits.ringStrong)
        )
        .contentShape(RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous))
        .onTapGesture { habits.selection = row.habit.id }
        .contextMenu { HabitMenu(habit: row.habit) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(row.habit.name)
    }

    private func progress(_ day: Date) -> Double {
        guard row.habit.type == Habit.companion.TYPE_REAL, row.habit.goal > 0 else { return 0 }
        return min(1, habits.value(row, day) / row.habit.goal)
    }
}

private struct HabitRowView: View {
    @Environment(HabitsModel.self) private var habits
    let row: HabitRow
    private var selected: Bool { habits.selection == row.habit.id }

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            HabitIcon(habit: row.habit, size: Theme.Habits.rowIcon)
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                Text(row.habit.name).font(.body.weight(.medium)).lineLimit(1)
                HStack(spacing: Theme.Space.s) {
                    if row.stats.currentStreak > 0 {
                        Label { Text("\(row.stats.currentStreak) \(row.stats.unit)") } icon: { Image(systemName: "flame.fill").foregroundStyle(.orange) }
                    }
                    Text(row.schedule).foregroundStyle(.secondary)
                }
                .font(.caption)
                .lineLimit(1)
            }
            Spacer(minLength: Theme.Space.s)
            HStack(spacing: Theme.Habits.dotSpacing) {
                ForEach(habits.lastDays(), id: \.self) { day in
                    DayDot(row: row, day: day)
                }
            }
        }
        .padding(.vertical, Theme.Space.s)
        .padding(.horizontal, Theme.Space.s)
        .background(
            RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous)
                .fill(selected ? AnyShapeStyle(HabitLook.color(row.habit).opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear))
        )
        .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))
        .onTapGesture { habits.selection = row.habit.id }
        .contextMenu { HabitMenu(habit: row.habit) }
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }
}

/// One day of the strip: weekday letter over a circle you can click.
private struct DayDot: View {
    @Environment(HabitsModel.self) private var habits
    let row: HabitRow
    let day: Date

    var body: some View {
        let isToday = habits.stamp(day) == habits.today
        VStack(spacing: Theme.Space.xxs) {
            Text(day.formatted(.dateTime.weekday(.narrow)))
                .font(.caption2.weight(isToday ? .bold : .regular))
                .foregroundStyle(isToday ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
            Button { habits.tap(row, on: day) } label: {
                CheckCircle(done: habits.isDone(row, day), progress: progress, color: HabitLook.color(row.habit), size: Theme.Habits.dot, emphasised: isToday)
            }
            .buttonStyle(.plain)
            .help(help)
            .contextMenu {
                if habits.value(row, day) > 0 { Button("Clear", systemImage: "xmark.circle") { habits.clear(row, on: day) } }
            }
        }
    }

    private var progress: Double {
        guard row.habit.type == Habit.companion.TYPE_REAL, row.habit.goal > 0 else { return 0 }
        return min(1, habits.value(row, day) / row.habit.goal)
    }

    private var help: String {
        let date = day.formatted(.dateTime.weekday(.wide).day().month())
        if row.habit.type == Habit.companion.TYPE_REAL {
            return "\(date): \(HabitLook.amount(habits.value(row, day))) / \(HabitLook.amount(row.habit.goal)) \(row.habit.unit)"
        }
        return habits.isDone(row, day) ? "\(date): done" : "\(date): click to check in"
    }
}

/// A filled circle with a check when done, a progress ring for partly logged amounts, a ring otherwise.
struct CheckCircle: View {
    let done: Bool
    let progress: Double
    let color: Color
    let size: CGFloat
    var emphasised = false

    var body: some View {
        ZStack {
            Circle().strokeBorder(emphasised ? AnyShapeStyle(color) : AnyShapeStyle(.tertiary), lineWidth: emphasised ? Theme.Habits.ringStrong : Theme.Habits.ring)
            if progress > 0 && !done {
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(color, style: StrokeStyle(lineWidth: Theme.Habits.progressRing, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(Theme.Habits.ring)
            }
            if done {
                Circle().fill(color)
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.45, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
        .animation(Motion.pop, value: done)
        .animation(Motion.pop, value: progress)
    }
}

/// Edit / archive / delete, shared by the row's context menu and the detail's menu.
private struct HabitMenu: View {
    @Environment(HabitsModel.self) private var habits
    let habit: Habit

    var body: some View {
        Button("Edit…", systemImage: "pencil") { habits.sheet = .edit(habit.id) }
        Button(habit.archivedAt == nil ? "Archive" : "Unarchive", systemImage: habit.archivedAt == nil ? "archivebox" : "tray.and.arrow.up") {
            habits.archive(habit)
        }
        Divider()
        Button("Delete…", systemImage: "trash", role: .destructive) { habits.confirmDelete = habit }
    }
}

// MARK: - Detail

private struct HabitDetail: View {
    @Environment(HabitsModel.self) private var habits
    let row: HabitRow

    var body: some View {
        let color = HabitLook.color(row.habit)
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.xl) {
                HStack(spacing: Theme.Space.l) {
                    HabitIcon(habit: row.habit, size: Theme.Habits.heroIcon)
                    VStack(alignment: .leading, spacing: Theme.Space.xs) {
                        Text(row.habit.name).font(.title.weight(.bold))
                        Text(subtitle).font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Menu { HabitMenu(habit: row.habit) } label: {
                        Image(systemName: "ellipsis.circle").font(.title2)
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("More")
                }
                CheckInCard(row: row, color: color).id(row.habit.id)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Theme.Space.m), count: 4), spacing: Theme.Space.m) {
                    StatCard(title: "Current streak", value: "\(row.stats.currentStreak)", unit: row.stats.unit, symbol: "flame.fill", tint: .orange)
                    StatCard(title: "Longest streak", value: "\(row.stats.longestStreak)", unit: row.stats.unit, symbol: "trophy.fill", tint: Theme.Habits.gold)
                    StatCard(title: "Check-ins", value: "\(row.stats.totalDays)", unit: "days", symbol: "checkmark.seal.fill", tint: color)
                    let month = habits.monthStats(row)
                    StatCard(title: "This month", value: "\(Int((month.rate * 100).rounded()))%", unit: "\(month.done) days", symbol: "chart.bar.fill", tint: .purple)
                }
                MonthMap(row: row, color: color)
                if !row.habit.encouragement.isEmpty {
                    Text("“\(row.habit.encouragement)”").font(.callout.italic()).foregroundStyle(.secondary)
                }
            }
            .padding(Theme.Space.xl)
            .frame(maxWidth: Theme.Detail.maxWidth)
            .frame(maxWidth: .infinity)
        }
        .background(
            LinearGradient(colors: [color.opacity(Theme.Habits.wash), .clear], startPoint: .top, endPoint: .center)
                .ignoresSafeArea()
        )
    }

    private var subtitle: String {
        var parts = [row.schedule]
        if row.habit.type == Habit.companion.TYPE_REAL { parts.append("goal \(HabitLook.amount(row.habit.goal)) \(row.habit.unit)".trimmingCharacters(in: .whitespaces)) }
        if row.archived { parts.append("archived") }
        return parts.joined(separator: " · ")
    }
}

/// The big check-in button for today (yes/no), or the amount logger (number habits).
private struct CheckInCard: View {
    @Environment(HabitsModel.self) private var habits
    let row: HabitRow
    let color: Color
    @State private var amount = ""
    @State private var amountInvalid = false

    var body: some View {
        let today = habits.date(habits.today)
        HStack(spacing: Theme.Space.l) {
            if row.habit.type == Habit.companion.TYPE_REAL {
                CheckCircle(done: row.doneToday, progress: row.habit.goal > 0 ? min(1, row.todayValue / row.habit.goal) : 0, color: color, size: Theme.Habits.bigCheck)
                VStack(alignment: .leading, spacing: Theme.Space.xs) {
                    Text("\(HabitLook.amount(row.todayValue)) / \(HabitLook.amount(row.habit.goal)) \(row.habit.unit)")
                        .font(.title2.weight(.semibold).monospacedDigit())
                    Text(row.doneToday ? "Goal reached today" : "Today").foregroundStyle(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: Theme.Space.xxs) {
                    TextField("Amount", text: $amount)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: Theme.Habits.amountField)
                        .onSubmit(addTyped)
                        .onChange(of: amount) { _, _ in amountInvalid = false }
                    if amountInvalid { Text("Enter a number above 0").font(.caption).foregroundStyle(.red) }
                }
                Button {
                    if amount.isEmpty { habits.tap(row, on: today) } else { addTyped() }
                } label: {
                    Label(amount.isEmpty ? "+\(HabitLook.amount(row.habit.step))" : "Add", systemImage: "plus")
                        .frame(minWidth: Theme.Habits.addButton)
                }
                .buttonStyle(.borderedProminent)
                .tint(color)
                .controlSize(.large)
            } else {
                Button { habits.tap(row, on: today) } label: {
                    HStack(spacing: Theme.Space.m) {
                        Image(systemName: row.doneToday ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: Theme.Habits.checkSymbol, weight: .semibold))
                            .foregroundStyle(row.doneToday ? AnyShapeStyle(.white) : AnyShapeStyle(color))
                        Text(row.doneToday ? "Done today" : "Check in today").font(.title3.weight(.semibold))
                        Spacer()
                        if row.doneToday { Text("Click to undo").font(.callout) }
                    }
                    .padding(.horizontal, Theme.Space.l)
                    .frame(height: Theme.Habits.checkInHeight)
                    .foregroundStyle(row.doneToday ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                    .background(
                        RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous)
                            .fill(row.doneToday ? AnyShapeStyle(color.gradient) : AnyShapeStyle(color.opacity(Theme.Opacity.chip)))
                    )
                    .contentShape(RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(row.habit.type == Habit.companion.TYPE_REAL ? Theme.Detail.cardPadding : 0)
        .background {
            if row.habit.type == Habit.companion.TYPE_REAL {
                RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous).fill(.background.secondary)
            }
        }
    }

    private func addTyped() {
        guard let value = Double(amount.replacingOccurrences(of: ",", with: ".")), value > 0 else {
            amountInvalid = true
            return
        }
        habits.add(row, amount: value, on: habits.date(habits.today))
        amount = ""
    }
}

private struct StatCard: View {
    let title: String
    let value: String
    let unit: String
    let symbol: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Image(systemName: symbol).font(.title3).foregroundStyle(tint)
            Text(value).font(.title.weight(.bold).monospacedDigit())
            Text(unit).font(.caption).foregroundStyle(.secondary)
            Text(title).font(.caption.weight(.medium)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Detail.cardPadding)
        .background(RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous).fill(tint.opacity(Theme.Opacity.chipFill)))
    }
}

/// The month heatmap: done days filled, partly logged days tinted, today ringed; click to check in.
private struct MonthMap: View {
    @Environment(HabitsModel.self) private var habits
    let row: HabitRow
    let color: Color

    var body: some View {
        @Bindable var habits = habits
        VStack(spacing: Theme.Space.m) {
            HStack {
                Text(habits.month.formatted(.dateTime.month(.wide).year())).font(.headline)
                Spacer()
                Button { step(-1) } label: { Image(systemName: "chevron.left") }.help("Previous month")
                Button("Today") { habits.month = .now }
                Button { step(1) } label: { Image(systemName: "chevron.right") }.help("Next month")
            }
            .buttonStyle(.borderless)
            HStack(spacing: 0) {
                ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, s in
                    Text(s).font(.caption.weight(.medium)).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                }
            }
            ForEach(Array(habits.monthGrid().enumerated()), id: \.offset) { _, week in
                HStack(spacing: 0) {
                    ForEach(Array(week.enumerated()), id: \.offset) { _, day in
                        Group {
                            if let day { cell(day) } else { Color.clear }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: Theme.Habits.monthCell)
                    }
                }
            }
        }
        .padding(Theme.Detail.cardPadding)
        .background(RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous).fill(.background.secondary))
    }

    private func cell(_ day: Date) -> some View {
        let done = habits.isDone(row, day)
        let partial = !done && habits.value(row, day) > 0
        let isToday = habits.stamp(day) == habits.today
        let future = habits.isFuture(day)
        return Button { habits.tap(row, on: day) } label: {
            Text("\(habits.calendar.component(.day, from: day))")
                .font(.callout.weight(done || isToday ? .semibold : .regular).monospacedDigit())
                .foregroundStyle(done ? AnyShapeStyle(.white) : future ? AnyShapeStyle(.tertiary) : AnyShapeStyle(.primary))
                .frame(width: Theme.Habits.monthDot, height: Theme.Habits.monthDot)
                .background(Circle().fill(done ? AnyShapeStyle(color) : partial ? AnyShapeStyle(color.opacity(Theme.Opacity.partial)) : AnyShapeStyle(.clear)))
                .overlay(Circle().strokeBorder(color, lineWidth: isToday && !done ? Theme.Habits.ringStrong : 0))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(future)
        .contextMenu {
            if habits.value(row, day) > 0 { Button("Clear", systemImage: "xmark.circle") { habits.clear(row, on: day) } }
        }
    }

    private var weekdaySymbols: [String] {
        let s = habits.calendar.veryShortStandaloneWeekdaySymbols
        let first = habits.calendar.firstWeekday - 1
        return Array(s[first...] + s[..<first])
    }

    private func step(_ n: Int) {
        habits.month = habits.calendar.date(byAdding: .month, value: n, to: habits.month) ?? habits.month
    }
}

// MARK: - Look

/// A habit's icon in a coloured circle: its emoji, or a symbol guessed from TickTick's icon name or the habit name.
struct HabitIcon: View {
    let habit: Habit
    let size: CGFloat

    var body: some View {
        let color = HabitLook.color(habit)
        ZStack {
            Circle().fill(color.opacity(Theme.Opacity.iconFill))
            if let emoji = HabitLook.emoji(habit.icon) {
                Text(emoji).font(.system(size: size * 0.5))
            } else {
                Image(systemName: HabitLook.symbol(habit)).font(.system(size: size * 0.44, weight: .semibold)).foregroundStyle(color)
            }
        }
        .frame(width: size, height: size)
    }
}

enum HabitLook {
    /// The habit's colour, or a palette colour picked from its id so each habit keeps one.
    static func color(_ habit: Habit) -> Color {
        if let c = Color(hex: habit.color) { return c }
        let i = abs(habit.id.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }) % Palette.colors.count
        return Color(hex: Palette.colors[i]) ?? .themeAccent
    }

    /// The icon when it is an emoji (TickTick's own icon names are not shown as text).
    static func emoji(_ icon: String?) -> String? {
        guard let icon, !icon.isEmpty, icon.count <= 2, icon.unicodeScalars.contains(where: { $0.properties.isEmojiPresentation || $0.value > 0x2000 }) else { return nil }
        return icon
    }

    private static let keywords: [(String, String)] = [
        ("read", "book.fill"), ("book", "book.fill"), ("đọc", "book.fill"),
        ("water", "drop.fill"), ("drink", "drop.fill"), ("nước", "drop.fill"),
        ("run", "figure.run"), ("chạy", "figure.run"), ("walk", "figure.walk"), ("đi bộ", "figure.walk"),
        ("gym", "dumbbell.fill"), ("exercise", "dumbbell.fill"), ("workout", "dumbbell.fill"), ("tập", "dumbbell.fill"),
        ("sleep", "bed.double.fill"), ("ngủ", "bed.double.fill"), ("wake", "sunrise.fill"), ("dậy", "sunrise.fill"),
        ("meditat", "brain.head.profile"), ("thiền", "brain.head.profile"),
        ("english", "character.bubble.fill"), ("learn", "graduationcap.fill"), ("study", "graduationcap.fill"), ("học", "graduationcap.fill"),
        ("code", "chevron.left.forwardslash.chevron.right"), ("write", "pencil"), ("viết", "pencil"), ("journal", "pencil"),
        ("fruit", "carrot.fill"), ("eat", "fork.knife"), ("ăn", "fork.knife"), ("vitamin", "pills.fill"),
        ("money", "banknote.fill"), ("tiết kiệm", "banknote.fill"), ("phone", "iphone.slash"),
        ("smile", "face.smiling.inverse"), ("cười", "face.smiling.inverse"), ("clean", "sparkles"), ("dọn", "sparkles"),
    ]

    static func symbol(_ habit: Habit) -> String {
        let text = ((habit.icon ?? "") + " " + habit.name).lowercased()
        return keywords.first { text.contains($0.0) }?.1 ?? "target"
    }

    /// 3, 2.5, 0.25 — no trailing zeros.
    static func amount(_ v: Double) -> String {
        v.formatted(.number.precision(.fractionLength(0...2)))
    }
}

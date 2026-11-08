import SwiftUI
@preconcurrency import ZenTaskShared

/// The Calendar area: Day / Week / Month with tasks and read-only events, plus an unscheduled-task panel.
struct CalendarView: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(TasksModel.self) private var tasks
    @State private var monitors: [Any] = []

    var body: some View {
        @Bindable var cal = cal
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                toolbar
                Divider()
                switch cal.mode {
                case .month, .multiWeek: MonthGrid()
                case .year: YearGrid()
                case .agenda: AgendaList()
                default: WeekGrid()
                }
            }
            Divider()
            UnscheduledPanel()
                .frame(width: Theme.Calendar.panelWidth)
        }
        .onAppear {
            cal.start(); tasks.start()
            installMonitors()
        }
        .onDisappear {
            monitors.forEach(NSEvent.removeMonitor)
            monitors = []
        }
        .background { ModeShortcuts() }
    }

    /// ⌘-scroll zooms the hour grid; ⌥ shows task checkboxes (as in TickTick).
    private func installMonitors() {
        guard monitors.isEmpty else { return }
        let model = cal
        if let zoom = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel, handler: { event in
            guard event.modifierFlags.contains(.command), model.mode.isGrid else { return event }
            let delta = event.hasPreciseScrollingDeltas ? event.scrollingDeltaY / 100 : event.scrollingDeltaY / 10
            MainActor.assumeIsolated { model.zoom(by: 1 + delta) }
            return nil
        }) { monitors.append(zoom) }
        if let flags = NSEvent.addLocalMonitorForEvents(matching: .flagsChanged, handler: { event in
            let down = event.modifierFlags.contains(.option)
            MainActor.assumeIsolated { if model.optionDown != down { model.optionDown = down } }
            return event
        }) { monitors.append(flags) }
    }

    private var toolbar: some View {
        @Bindable var cal = cal
        return HStack(spacing: Theme.Space.m) {
            Text(cal.title).font(.title2.weight(.semibold)).lineLimit(1)
            Spacer()
            Button("Today") { cal.today() }
                .keyboardShortcut("t", modifiers: .command)
            HStack(spacing: Theme.Space.xxs) {
                Button { cal.previous() } label: { Image(systemName: "chevron.left").frame(width: Theme.Space.xl) }
                    .keyboardShortcut(.leftArrow, modifiers: .command)
                    .help("Previous")
                Button { cal.next() } label: { Image(systemName: "chevron.right").frame(width: Theme.Space.xl) }
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                    .help("Next")
            }
            Menu {
                ForEach(CalendarModel.Mode.allCases) { mode in
                    if mode == .multiDay { Divider() }
                    Button {
                        cal.mode = mode
                    } label: {
                        if cal.mode == mode { Label(mode.rawValue, systemImage: "checkmark") } else { Text(mode.rawValue) }
                        if let detail = mode.detail { Text(detail) }
                    }
                    .modifier(LetterShortcut(mode: mode))
                }
            } label: {
                Text(cal.mode.rawValue).fontWeight(.medium)
            }
            .menuStyle(.button)
            .fixedSize()
            .help("View: D Day · W Week · M Month · Y Year · A Agenda (or 1–5)")
        }
        .controlSize(.large)
        .padding(.horizontal, Theme.Space.l)
        .padding(.vertical, Theme.Space.m)
    }
}

// MARK: - Week / Day

private struct WeekGrid: View {
    @Environment(CalendarModel.self) private var cal
    @State private var pinchBase: CGFloat = 1

    var body: some View {
        VStack(spacing: 0) {
            header
            allDayRow
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    HStack(alignment: .top, spacing: 0) {
                        hourLabels
                        ForEach(Array(cal.days.enumerated()), id: \.offset) { _, day in
                            DayColumn(day: day)
                        }
                    }
                    .id("grid")
                    .overlay(alignment: .topLeading) { Color.clear.frame(height: 1).offset(y: 7 * cal.hourHeight).id("seven") }
                }
                .onAppear { proxy.scrollTo("seven", anchor: .top) }
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in
                            cal.zoom(by: value.magnification / pinchBase)
                            pinchBase = value.magnification
                        }
                        .onEnded { _ in pinchBase = 1 },
                )
            }
        }
    }

    private var header: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: Theme.Calendar.timeGutter)
            ForEach(Array(cal.days.enumerated()), id: \.offset) { _, day in
                let date = cal.date(of: day)
                let isToday = cal.calendar.isDateInToday(date)
                HStack(spacing: Theme.Space.xs) {
                    Text(date.formatted(.dateTime.weekday(.abbreviated))).foregroundStyle(.secondary)
                    Text(date.formatted(.dateTime.day()))
                        .fontWeight(.semibold)
                        .foregroundStyle(isToday ? Color.white : Color.primary)
                        .padding(.horizontal, Theme.Space.s)
                        .padding(.vertical, Theme.Space.xxs)
                        .background(isToday ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear), in: Capsule())
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: Theme.Calendar.headerHeight)
    }

    private var allDayRow: some View {
        HStack(alignment: .top, spacing: 0) {
            Text("All day").font(.caption).foregroundStyle(.secondary)
                .frame(width: Theme.Calendar.timeGutter, alignment: .trailing)
                .padding(.trailing, Theme.Space.xs)
            ForEach(Array(cal.days.enumerated()), id: \.offset) { _, day in
                VStack(spacing: Theme.Space.xxs) {
                    ForEach(day.allDay, id: \.id) { EntryChip(entry: $0, compact: true) }
                }
                .frame(maxWidth: .infinity, minHeight: Theme.Calendar.allDayMin, alignment: .top)
                .padding(Theme.Space.xxs)
                .contentShape(Rectangle())
                .dropDestination(for: String.self) { ids, _ in
                    let live = cal.liveTaskIds(ids)
                    live.forEach { cal.schedule(taskId: $0, on: day, minute: nil) }
                    return !live.isEmpty
                }
            }
        }
    }

    private var hourLabels: some View {
        VStack(spacing: 0) {
            ForEach(0..<24, id: \.self) { h in
                Text(h == 0 ? "" : String(format: "%02d:00", h))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(width: Theme.Calendar.timeGutter - Theme.Space.s, height: cal.hourHeight, alignment: .topTrailing)
                    .offset(y: -Theme.Calendar.labelNudge)
                    .padding(.trailing, Theme.Space.s)
            }
        }
    }
}

private struct DayColumn: View {
    @Environment(CalendarModel.self) private var cal
    let day: CalendarDay

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                VStack(spacing: 0) {
                    ForEach(0..<24, id: \.self) { _ in
                        Rectangle().fill(.clear).frame(height: cal.hourHeight)
                            .overlay(alignment: .top) { Divider() }
                    }
                }
                ForEach(day.timed, id: \.entry.id) { placed in
                    let width = geo.size.width / CGFloat(max(placed.columns, 1))
                    EntryBlock(entry: placed.entry)
                        .frame(width: max(width - 2, 0), height: max(CGFloat(placed.endMinute - placed.startMinute) / 60 * cal.hourHeight - 2, 18))
                        .offset(x: CGFloat(placed.column) * width + 1, y: CGFloat(placed.startMinute) / 60 * cal.hourHeight + 1)
                }
                if cal.calendar.isDateInToday(cal.date(of: day)) { NowLine(hourHeight: cal.hourHeight) }
            }
            .dropDestination(for: String.self) { ids, location in
                let live = cal.liveTaskIds(ids)
                let minute = Int(location.y / cal.hourHeight * 60)
                live.forEach { cal.schedule(taskId: $0, on: day, minute: minute) }
                return !live.isEmpty
            }
        }
        .frame(height: 24 * cal.hourHeight)
        .overlay(alignment: .leading) { Divider() }
    }
}

private struct NowLine: View {
    let hourHeight: CGFloat

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let c = Calendar.current.dateComponents([.hour, .minute], from: context.date)
            let y = CGFloat((c.hour ?? 0) * 60 + (c.minute ?? 0)) / 60 * hourHeight
            HStack(spacing: 0) {
                Circle().fill(.red).frame(width: Theme.Calendar.dot + 1, height: Theme.Calendar.dot + 1)
                Rectangle().fill(.red).frame(height: 2)
            }
            .offset(x: -4, y: y - 4)
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Month

private struct MonthGrid: View {
    @Environment(CalendarModel.self) private var cal

    var body: some View {
        let month = cal.mode == .multiWeek ? -1 : cal.calendar.component(.month, from: cal.anchor)
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(Array(cal.days.prefix(7).enumerated()), id: \.offset) { _, day in
                    Text(cal.date(of: day).formatted(.dateTime.weekday(.abbreviated)))
                        .font(.callout).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                }
            }
            .frame(height: Theme.Calendar.headerHeight)
            Divider()
            GeometryReader { geo in
                let rows = max(cal.days.count / 7, 1)
                VStack(spacing: 0) {
                    ForEach(0..<rows, id: \.self) { r in
                        HStack(spacing: 0) {
                            ForEach(0..<7, id: \.self) { c in
                                let i = r * 7 + c
                                if i < cal.days.count {
                                    MonthCell(day: cal.days[i], inMonth: month == -1 || cal.calendar.component(.month, from: cal.date(of: cal.days[i])) == month)
                                        .frame(width: geo.size.width / 7, height: geo.size.height / CGFloat(rows))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct MonthCell: View {
    @Environment(CalendarModel.self) private var cal
    let day: CalendarDay
    let inMonth: Bool

    var body: some View {
        let date = cal.date(of: day)
        let items = Array(day.allDay) + day.timed.map(\.entry)
        VStack(alignment: .leading, spacing: Theme.Space.xxs) {
            Text(date.formatted(.dateTime.day()))
                .font(.callout.weight(.semibold))
                .foregroundStyle(cal.calendar.isDateInToday(date) ? Color.white : (inMonth ? Color.primary : Color.secondary))
                .padding(.horizontal, Theme.Space.s)
                .padding(.vertical, Theme.Space.xxs)
                .background(cal.calendar.isDateInToday(date) ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear), in: Capsule())
            ForEach(items.prefix(Theme.Calendar.monthItems), id: \.id) { EntryChip(entry: $0, compact: true) }
            if items.count > Theme.Calendar.monthItems {
                Text("\(items.count - Theme.Calendar.monthItems) more").font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(Theme.Space.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .overlay(alignment: .topLeading) { Rectangle().stroke(.quaternary, lineWidth: 0.5) }
        .opacity(inMonth ? 1 : 0.6)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { cal.anchor = date; cal.mode = .day }
        .dropDestination(for: String.self) { ids, _ in
            let live = cal.liveTaskIds(ids)
            live.forEach { cal.schedule(taskId: $0, on: day, minute: nil) }
            return !live.isEmpty
        }
    }
}

// MARK: - Entries

/// Events take their calendar's colour; tasks their priority colour (grey for none).
private func entryColor(_ entry: CalendarEntry) -> Color {
    if entry.kind == "task" { return entry.priority == 0 ? Color.gray : Theme.PriorityColor.color(Int32(entry.priority)) }
    return Color(hex: entry.color) ?? .accentColor
}

/// A timed block in the Week/Day grid. Tasks are solid bars with white text; events are tinted.
/// Holding ⌥ shows a task's checkbox, as in TickTick.
private struct EntryBlock: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(CalendarModel.self) private var cal
    let entry: CalendarEntry
    @State private var showing = false

    var body: some View {
        let color = entryColor(entry)
        let isTask = entry.kind == "task"
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.Space.xs) {
                if isTask && cal.optionDown { TaskCheck(entry: entry) }
                Text(entry.title).font(.callout.weight(.medium)).strikethrough(entry.done).lineLimit(2)
            }
            Text(Date(timeIntervalSince1970: Double(entry.start) / 1000).formatted(date: .omitted, time: .shortened))
                .font(.caption).opacity(0.85)
        }
        .foregroundStyle(isTask ? AnyShapeStyle(Color.white) : AnyShapeStyle(.primary))
        .padding(.horizontal, Theme.Space.xs + Theme.Space.xxs)
        .padding(.vertical, Theme.Space.xxs)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(isTask ? AnyShapeStyle(color.opacity(entry.done ? 0.45 : 0.9)) : AnyShapeStyle(color.opacity(Theme.Calendar.eventFill)),
                    in: RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
        .overlay(alignment: .leading) { if !isTask { RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 3) } }
        .clipShape(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { showing = true }
        .popover(isPresented: $showing) { EntryDetail(entry: entry) }
        .modifier(DraggableTask(entry: entry))
        .contextMenu { if isTask, let t = tasks.index?.task(id: entry.id) { TaskMenu(task: t) } }
    }
}

/// A one-line chip (all-day row, month cells).
private struct EntryChip: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(TasksModel.self) private var tasks
    let entry: CalendarEntry
    let compact: Bool
    @State private var showing = false

    var body: some View {
        let color = entryColor(entry)
        let isTask = entry.kind == "task"
        HStack(spacing: Theme.Space.xs) {
            if isTask {
                if cal.optionDown { TaskCheck(entry: entry) }
            } else {
                Circle().fill(color).frame(width: Theme.Calendar.dot, height: Theme.Calendar.dot)
            }
            Text(entry.title).lineLimit(1).strikethrough(entry.done)
            Spacer(minLength: 0)
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(isTask ? AnyShapeStyle(Color.white) : AnyShapeStyle(.primary))
        .padding(.horizontal, Theme.Space.xs + Theme.Space.xxs)
        .padding(.vertical, Theme.Space.xxs)
        .background(isTask ? AnyShapeStyle(color.opacity(entry.done ? 0.45 : 0.9)) : AnyShapeStyle(color.opacity(Theme.Calendar.eventFill)),
                    in: RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { showing = true }
        .popover(isPresented: $showing) { EntryDetail(entry: entry) }
        .modifier(DraggableTask(entry: entry))
        .contextMenu { if isTask, let t = tasks.index?.task(id: entry.id) { TaskMenu(task: t) } }
    }
}

/// Checkbox shown on task entries while ⌥ is held; clicking completes / reopens.
private struct TaskCheck: View {
    @Environment(TasksModel.self) private var tasks
    let entry: CalendarEntry

    var body: some View {
        Button {
            if entry.done { tasks.core.actions.uncomplete(id: entry.id) } else { tasks.core.actions.complete(id: entry.id) }
        } label: {
            Image(systemName: entry.done ? "checkmark.square.fill" : "square")
        }
        .buttonStyle(.plain)
        .help(entry.done ? "Mark as not done" : "Complete")
    }
}

/// Only tasks can be dragged (events are read-only).
private struct DraggableTask: ViewModifier {
    let entry: CalendarEntry

    func body(content: Content) -> some View {
        if entry.kind == "task" { content.draggable(entry.id) } else { content }
    }
}

/// Menu item shortcut: the mode's letter (D, W, M, Y, A).
private struct LetterShortcut: ViewModifier {
    let mode: CalendarModel.Mode

    func body(content: Content) -> some View {
        if let keys = mode.keys { content.keyboardShortcut(KeyEquivalent(keys.letter), modifiers: []) } else { content }
    }
}

/// Hidden buttons for the number shortcuts (1–5), alongside the letters in the menu.
private struct ModeShortcuts: View {
    @Environment(CalendarModel.self) private var cal

    var body: some View {
        ZStack {
            ForEach(CalendarModel.Mode.allCases.filter { $0.keys != nil }) { mode in
                Button("") { cal.mode = mode }
                    .keyboardShortcut(KeyEquivalent(mode.keys!.number), modifiers: [])
            }
        }
        .opacity(0)
        .accessibilityHidden(true)
    }
}

/// Popover: a task can be completed or opened; an event is read-only.
private struct EntryDetail: View {
    @Environment(TasksModel.self) private var tasks
    let entry: CalendarEntry

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text(entry.title.isEmpty ? "Untitled" : entry.title).font(.title3.weight(.semibold))
            Label(timeText, systemImage: "clock").foregroundStyle(.secondary)
            if !entry.location.isEmpty { Label(entry.location, systemImage: "mappin.and.ellipse").foregroundStyle(.secondary) }
            if entry.kind == "task" {
                HStack {
                    Button(entry.done ? "Mark as not done" : "Complete") {
                        if entry.done { tasks.core.actions.uncomplete(id: entry.id) } else { tasks.core.actions.complete(id: entry.id) }
                    }
                    Button("Remove date") { tasks.core.actions.setDue(id: entry.id, due: nil, allDay: false) }
                }
                .controlSize(.large)
            } else {
                Label("From your calendar (read-only)", systemImage: "calendar").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(Theme.Space.l)
        .frame(width: Theme.Calendar.popoverWidth, alignment: .leading)
    }

    private var timeText: String {
        let s = Date(timeIntervalSince1970: Double(entry.start) / 1000)
        let e = Date(timeIntervalSince1970: Double(entry.end) / 1000)
        if entry.allDay { return s.formatted(date: .abbreviated, time: .omitted) + " · all day" }
        return s.formatted(date: .abbreviated, time: .shortened) + " – " + e.formatted(date: .omitted, time: .shortened)
    }
}

// MARK: - Year

private struct YearGrid: View {
    @Environment(CalendarModel.self) private var cal

    var body: some View {
        let byDay = Dictionary(uniqueKeysWithValues: cal.days.map { (cal.calendar.startOfDay(for: cal.date(of: $0)), $0.allDay.count + $0.timed.count) })
        let year = cal.calendar.component(.year, from: cal.anchor)
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Theme.Space.xl), count: 4), spacing: Theme.Space.xl) {
                ForEach(1...12, id: \.self) { month in
                    MiniMonth(year: year, month: month, counts: byDay)
                }
            }
            .padding(Theme.Space.xl)
        }
    }
}

private struct MiniMonth: View {
    @Environment(CalendarModel.self) private var cal
    let year: Int
    let month: Int
    let counts: [Date: Int]

    var body: some View {
        let first = cal.calendar.date(from: DateComponents(year: year, month: month, day: 1))!
        let gridStart = cal.calendar.dateInterval(of: .weekOfYear, for: first)?.start ?? first
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(first.formatted(.dateTime.month(.wide))).font(.headline)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(Theme.Calendar.yearCell), spacing: 2), count: 7), spacing: 2) {
                ForEach(0..<42, id: \.self) { i in
                    let date = cal.calendar.date(byAdding: .day, value: i, to: gridStart)!
                    let inMonth = cal.calendar.component(.month, from: date) == month
                    let busy = (counts[date] ?? 0) > 0
                    Button {
                        cal.anchor = date
                        cal.mode = .day
                    } label: {
                        Text(date.formatted(.dateTime.day()))
                            .font(.caption)
                            .frame(width: Theme.Calendar.yearCell, height: Theme.Calendar.yearCell)
                            .foregroundStyle(cal.calendar.isDateInToday(date) ? Color.white : (inMonth ? Color.primary : Color.secondary.opacity(0.4)))
                            .background(cal.calendar.isDateInToday(date) ? AnyShapeStyle(.tint) : (busy && inMonth ? AnyShapeStyle(.tint.opacity(0.18)) : AnyShapeStyle(.clear)), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!inMonth)
                }
            }
        }
    }
}

// MARK: - Agenda

private struct AgendaList: View {
    @Environment(CalendarModel.self) private var cal

    var body: some View {
        let days = cal.days.filter { !$0.allDay.isEmpty || !$0.timed.isEmpty }
        if days.isEmpty {
            ContentUnavailableView("Nothing in the next 30 days", systemImage: "calendar")
        } else {
            List {
                ForEach(days, id: \.dayStart) { day in
                    Section(cal.date(of: day).formatted(.dateTime.weekday(.wide).day().month(.wide))) {
                        ForEach(Array(day.allDay) + day.timed.map(\.entry), id: \.id) { entry in
                            HStack(spacing: Theme.Space.m) {
                                Text(entry.allDay ? "All day" : Date(timeIntervalSince1970: Double(entry.start) / 1000).formatted(date: .omitted, time: .shortened))
                                    .font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                                    .frame(width: Theme.Calendar.timeGutter + Theme.Space.l, alignment: .leading)
                                EntryChip(entry: entry, compact: false)
                            }
                            .padding(.vertical, Theme.Space.xxs)
                        }
                    }
                }
            }
            .listStyle(.inset)
        }
    }
}

// MARK: - Unscheduled panel

/// Tasks of a chosen list (or all lists) without a date, to drag onto the calendar.
private struct UnscheduledPanel: View {
    @Environment(TasksModel.self) private var tasks
    @AppStorage("calendar.panelList") private var listId = ""

    var body: some View {
        let rows = (tasks.index?.allTasks ?? []).filter {
            $0.isOpen && $0.trashedAt == nil && $0.startDate == nil && $0.dueDate == nil && $0.parentId == nil &&
                (listId.isEmpty || $0.projectId == listId)
        }
        VStack(alignment: .leading, spacing: 0) {
            Picker(selection: $listId) {
                Label("All lists", systemImage: "square.stack").tag("")
                Label("Inbox", systemImage: "tray").tag("inbox")
                Divider()
                ForEach(tasks.listProjects, id: \.id) { p in
                    Label { Text(p.name) } icon: { Image(systemName: "list.bullet") }.tag(p.id)
                }
            } label: {
                EmptyView()
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .controlSize(.large)
            .padding(.horizontal, Theme.Space.l)
            .padding(.top, Theme.Space.l)
            HStack {
                Text("No date").font(.headline)
                Spacer()
                Text("\(rows.count)").foregroundStyle(.secondary).monospacedDigit()
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.top, Theme.Space.m)
            Text("Drag a task onto the calendar to give it a time").font(.caption).foregroundStyle(.secondary)
                .padding(.horizontal, Theme.Space.l).padding(.bottom, Theme.Space.s)
            List(rows.prefix(300), id: \.id) { task in
                HStack(spacing: Theme.Space.s) {
                    Image(systemName: "square").foregroundStyle(Theme.PriorityColor.color(task.priority))
                    Text(task.title).lineLimit(2)
                }
                .padding(.vertical, Theme.Space.xxs)
                .draggable(task.id)
                .contextMenu { TaskMenu(task: task) }
            }
            .listStyle(.inset)
            .overlay { if rows.isEmpty { ContentUnavailableView("Nothing without a date", systemImage: "checkmark.circle") } }
        }
    }
}

extension ZTask {
    var isOpen: Bool { status == 0 }
}

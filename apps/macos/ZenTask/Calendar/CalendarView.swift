import SwiftUI
@preconcurrency import ZenTaskShared

/// The Calendar area: Day / Week / Month with tasks and read-only events, plus an unscheduled-task panel.
struct CalendarView: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(TasksModel.self) private var tasks
    @State private var monitors: [Any] = []
    @AppStorage("calendar.showPanel") private var showPanel = true
    @AppStorage("calendar.showSideBar") private var showSideBar = false
    @State private var addingTask = false
    @State private var showingOptions = false

    var body: some View {
        @Bindable var cal = cal
        HStack(spacing: 0) {
            if showSideBar {
                CalendarSideBar()
                    .frame(width: Theme.Calendar.sideBarWidth)
                    .transition(Motion.slide(.leading))
                Divider()
            }
            VStack(spacing: 0) {
                toolbar
                Divider()
                switch cal.mode {
                case .month, .multiWeek: MonthGrid()
                case .year: YearGrid()
                case .agenda: AgendaTimeline()
                default: WeekGrid()
                }
            }
            if showPanel {
                Divider()
                TaskPanel(close: { withMotion { showPanel = false } })
                    .frame(width: Theme.Panel.tasksWidth)
                    .transition(Motion.slide(.trailing))
            }
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
            Button {
                withMotion { showSideBar.toggle() }
            } label: {
                Image(systemName: "sidebar.left").symbolVariant(showSideBar ? .fill : .none)
            }
            .buttonStyle(.borderless)
            .font(.title3)
            .help(showSideBar ? "Hide the month and filters" : "Show the month and filters")
            Text(cal.title).font(.title2.weight(.semibold)).lineLimit(1)
            Spacer()
            Button { addingTask = true } label: { Image(systemName: "plus") }
                .help("New task on this day")
                .popover(isPresented: $addingTask) {
                    TaskComposer(start: .allDay(cal.calendar.startOfDay(for: cal.anchor))) { addingTask = false }.environment(tasks)
                }
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
            Menu {
                Button("View options…", systemImage: "slider.horizontal.3") { showingOptions = true }
                Toggle("Arrange tasks", systemImage: "sidebar.right", isOn: Binding(get: { showPanel }, set: { v in
                    withMotion { showPanel = v }
                }))
                Toggle("Month and filters", systemImage: "sidebar.left", isOn: Binding(get: { showSideBar }, set: { v in
                    withMotion { showSideBar = v }
                }))
                Divider()
                SettingsLink { Label("Calendar accounts…", systemImage: "person.crop.circle") }
            } label: {
                Image(systemName: "ellipsis")
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .fixedSize()
            .help("View options, task panel, calendars")
            .sheet(isPresented: $showingOptions) { CalendarViewOptionsSheet().environment(cal) }
        }
        .controlSize(.large)
        .padding(.horizontal, Theme.Space.l)
        .padding(.vertical, Theme.Space.m)
    }
}

// MARK: - Week / Day

struct WeekGrid: View {
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
                    if let lunar = cal.lunar(date) {
                        Text(lunar).font(.caption).foregroundStyle(.secondary)
                    }
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
    /// Where a dragged task would land (snapped minute), while a drag hovers this column.
    @State private var dropMinute: Int?
    /// A range being dragged out on empty space, then the new-task card for it.
    @State private var draft: (start: Int, end: Int)?
    @State private var composing = false

    var body: some View {
        let snap = Theme.Calendar.snapMinutes
        ZStack(alignment: .topLeading) {
            VStack(spacing: 0) {
                ForEach(0..<24, id: \.self) { _ in
                    Rectangle().fill(.clear).frame(height: cal.hourHeight)
                        .overlay(alignment: .top) { Divider() }
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 4)
                    .onChanged { v in
                        let a = minute(at: v.startLocation.y), b = minute(at: v.location.y)
                        let lo = min(a, b), hi = max(max(a, b), lo + snap)
                        if draft?.start != lo || draft?.end != hi { withMotion { draft = (lo, min(hi, 24 * 60)) } }
                    }
                    .onEnded { _ in composing = draft != nil },
            )
            GeometryReader { geo in
                ForEach(day.timed, id: \.entry.id) { placed in
                    let width = geo.size.width / CGFloat(max(placed.columns, 1))
                    EntryBlock(entry: placed.entry)
                        .frame(width: max(width - 2, 0), height: max(CGFloat(placed.endMinute - placed.startMinute) / 60 * cal.hourHeight - 2, 18))
                        .offset(x: CGFloat(placed.column) * width + 1, y: CGFloat(placed.startMinute) / 60 * cal.hourHeight + 1)
                }
            }
            if cal.calendar.isDateInToday(cal.date(of: day)) { NowLine(hourHeight: cal.hourHeight) }
            if let m = dropMinute {
                GhostBlock(start: m, end: m + Theme.Calendar.taskMinutes, dayStart: cal.date(of: day), hourHeight: cal.hourHeight)
                    .allowsHitTesting(false)
            }
            if let d = draft {
                GhostBlock(start: d.start, end: d.end, dayStart: cal.date(of: day), hourHeight: cal.hourHeight)
                    .popover(isPresented: $composing, arrowEdge: .trailing) {
                        TaskComposer(start: .span(time(d.start), time(d.end))) { composing = false }
                    }
            }
        }
        .frame(height: 24 * cal.hourHeight)
        .overlay(alignment: .leading) { Divider() }
        .onDrop(of: [.plainText], delegate: ColumnDrop(model: cal, dayStart: day.dayStart, hourHeight: cal.hourHeight, minute: $dropMinute))
        .onChange(of: composing) { _, open in if !open { withMotion { draft = nil } } }
    }

    /// The minute under a y position, snapped to the 15-minute grid.
    private func minute(at y: CGFloat) -> Int {
        let raw = Int(y / cal.hourHeight * 60)
        let snap = Theme.Calendar.snapMinutes
        return max(0, min(24 * 60 - snap, (raw / snap) * snap))
    }

    private func time(_ minute: Int) -> Date { cal.date(of: day).addingTimeInterval(Double(minute) * 60) }
}

/// A dashed placeholder showing where a task will go, with its time ("13:15 – 13:45").
private struct GhostBlock: View {
    let start: Int
    let end: Int
    let dayStart: Date
    let hourHeight: CGFloat

    var body: some View {
        let from = dayStart.addingTimeInterval(Double(start) * 60)
        let to = dayStart.addingTimeInterval(Double(end) * 60)
        Text("\(from.formatted(date: .omitted, time: .shortened)) – \(to.formatted(date: .omitted, time: .shortened))")
            .font(.caption.weight(.semibold).monospacedDigit())
            .foregroundStyle(.tint)
            .padding(Theme.Space.xs)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .frame(height: max(CGFloat(end - start) / 60 * hourHeight - 2, Theme.Calendar.ghostMin), alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous).fill(.tint.opacity(Theme.Opacity.chip)))
            .overlay(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous)
                .strokeBorder(.tint, style: StrokeStyle(lineWidth: Theme.Habits.ringStrong, dash: [Theme.Space.xs, Theme.Space.xxs])))
            .padding(.horizontal, 1)
            .offset(y: CGFloat(start) / 60 * hourHeight + 1)
    }
}

/// Drop target for a day column: tracks the snapped minute under the pointer (for the placeholder) and
/// schedules the dropped task ids there.
private struct ColumnDrop: DropDelegate {
    let model: CalendarModel
    let dayStart: Int64
    let hourHeight: CGFloat
    @Binding var minute: Int?

    private func snapped(_ info: DropInfo) -> Int {
        let snap = Theme.Calendar.snapMinutes
        // Centre the default-length block on the pointer.
        let raw = Int(info.location.y / hourHeight * 60) - Theme.Calendar.taskMinutes / 2
        return max(0, min(24 * 60 - snap, Int((Double(raw) / Double(snap)).rounded()) * snap))
    }

    func validateDrop(info: DropInfo) -> Bool { info.hasItemsConforming(to: [.plainText]) }

    func dropEntered(info: DropInfo) { withMotion { minute = snapped(info) } }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        let m = snapped(info)
        if m != minute { withMotion { minute = m } }
        return DropProposal(operation: .move)
    }

    func dropExited(info: DropInfo) { withMotion { minute = nil } }

    func performDrop(info: DropInfo) -> Bool {
        let m = snapped(info)
        let model = self.model, dayStart = self.dayStart
        withMotion { minute = nil }
        let providers = info.itemProviders(for: [.plainText])
        guard !providers.isEmpty else { return false }
        for provider in providers {
            _ = provider.loadObject(ofClass: NSString.self) { object, _ in
                guard let id = object as? String else { return }
                _Concurrency.Task { @MainActor in model.schedule(taskId: id, dayStart: dayStart, minute: m) }
            }
        }
        return true
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

struct MonthGrid: View {
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
            HStack {
                Text(date.formatted(.dateTime.day()))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(cal.calendar.isDateInToday(date) ? Color.white : (inMonth ? Color.primary : Color.secondary))
                    .padding(.horizontal, Theme.Space.s)
                    .padding(.vertical, Theme.Space.xxs)
                    .background(cal.calendar.isDateInToday(date) ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear), in: Capsule())
                Spacer(minLength: 0)
                if let lunar = cal.lunar(date) { Text(lunar).font(.caption).foregroundStyle(.secondary) }
            }
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
func entryColor(_ entry: CalendarEntry) -> Color {
    if entry.kind == "task" {
        if let list = Color(hex: entry.color) { return list } // coloured by list
        return entry.priority == 0 ? Color.secondary : Theme.PriorityColor.color(Int32(entry.priority))
    }
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
struct TaskCheck: View {
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
struct DraggableTask: ViewModifier {
    let entry: CalendarEntry

    func body(content: Content) -> some View {
        if entry.kind == "task" { content.draggable(entry.id) } else { content }
    }
}

/// Menu item shortcut: the mode's letter (D, W, M, Y, A).
private struct LetterShortcut: ViewModifier {
    @Environment(\.searchOpen) private var searchOpen
    let mode: CalendarModel.Mode

    func body(content: Content) -> some View {
        if let keys = mode.keys, !searchOpen { content.keyboardShortcut(KeyEquivalent(keys.letter), modifiers: []) } else { content }
    }
}

/// Hidden buttons for the number shortcuts (1–5), alongside the letters in the menu.
private struct ModeShortcuts: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(\.searchOpen) private var searchOpen

    var body: some View {
        if !searchOpen { shortcuts }
    }

    private var shortcuts: some View {
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
struct EntryDetail: View {
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
            } else if entry.kind == "habit" {
                Button(entry.done ? "Undo check-in" : "Check in") { tasks.core.toggleHabitEntry(entryId: entry.id) }
                    .controlSize(.large)
                Label("A habit · open it in Habits (⌘3)", systemImage: "target").font(.caption).foregroundStyle(.secondary)
            } else if entry.kind == "countdown" {
                Label("A countdown · edit it in Countdown (⌘4)", systemImage: "hourglass").font(.caption).foregroundStyle(.secondary)
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

extension ZTask {
    var isOpen: Bool { status == 0 }
}

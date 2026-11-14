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
    @State private var pickingMode = false

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
                Group {
                    switch cal.mode {
                    case .month, .multiWeek: MonthGrid()
                    case .year: YearGrid()
                    case .agenda: AgendaTimeline()
                    default: WeekGrid()
                    }
                }
                .animation(Motion.fade, value: cal.mode)
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
            cal.setVisible(true)
            NSApp.windows.forEach { $0.acceptsMouseMovedEvents = true }
            installMonitors()
        }
        .onDisappear {
            cal.setVisible(false)
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
        // A drag cancelled with Esc never tells the drop target; the next ordinary mouse event clears the placeholder.
        // Esc during a range drag cancels the range.
        if let ends = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .mouseMoved, .leftMouseUp, .leftMouseDown], handler: { event in
            let isEsc = event.type == .keyDown && event.keyCode == 53
            let isKey = event.type == .keyDown
            let swallow = MainActor.assumeIsolated { () -> Bool in
                if isKey {
                    guard isEsc, model.dropHover != nil || (model.newRange != nil && !model.composingRange) else { return false }
                    withMotion { model.cancelDrags() }
                    return true
                }
                if model.dropHover != nil { withMotion { model.dropHover = nil } }
                return false
            }
            return swallow ? nil : event
        }) { monitors.append(ends) }
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
            Button { pickingMode.toggle() } label: {
                HStack(spacing: Theme.Space.xs) {
                    Text(cal.mode.rawValue).fontWeight(.medium)
                    Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                }
            }
            .help("View: D Day · W Week · M Month · Y Year · A Agenda (or 1–5)")
            .popover(isPresented: $pickingMode, arrowEdge: .bottom) { ModePicker { pickingMode = false }.environment(cal) }
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
    @AppStorage(Theme.Calendar.workStartKey) private var workStart = Theme.Calendar.workStart
    @AppStorage(Theme.Calendar.workEndKey) private var workEnd = Theme.Calendar.workEnd

    var body: some View {
        VStack(spacing: 0) {
            header
            allDayRow
            Divider()
            GeometryReader { viewport in
                ScrollViewReader { proxy in
                    ScrollView {
                        HStack(alignment: .top, spacing: 0) {
                            hourLabels
                            if let zone = extraZone { zoneLabels(zone) }
                            ForEach(Array(cal.days.enumerated()), id: \.offset) { _, day in
                                DayColumn(day: day)
                            }
                        }
                        .id("grid")
                        .background(alignment: .top) {
                            VStack(spacing: 0) {
                                ForEach(0..<24, id: \.self) { h in Color.clear.frame(height: cal.hourHeight).id("hour-\(h)") }
                            }
                            // Scrolling to an hour leaves room for its label, which sits half above the line.
                            .offset(y: -Theme.Calendar.labelRoom)
                        }
                    }
                    .onAppear { showWorkingHours(viewport.size.height, proxy) }
                    .onChange(of: workStart) { showWorkingHours(viewport.size.height, proxy) }
                    .onChange(of: workEnd) { showWorkingHours(viewport.size.height, proxy) }
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
    }

    /// Opening the calendar fits the working hours (Settings → Calendars, 08:00–21:00 by default) into the
    /// window and scrolls to their start; ⌘-scroll or pinch still zooms from there.
    private func showWorkingHours(_ height: CGFloat, _ proxy: ScrollViewProxy) {
        let start = max(0, min(23, workStart))
        let end = max(start + 1, min(24, workEnd))
        if height > 0 { cal.fitHours(end - start, into: height - Theme.Calendar.labelRoom) }
        DispatchQueue.main.async { proxy.scrollTo("hour-\(start)", anchor: .top) }
    }

    private var extraZone: TimeZone? {
        let id = cal.viewOptions.extraZone
        return id.isEmpty ? nil : TimeZone(identifier: id)
    }

    /// Width of the time column(s) on the left.
    private var gutter: CGFloat { Theme.Calendar.timeGutter * (extraZone == nil ? 1 : 2) }

    /// The second zone's clock at each of this zone's hours (for the first shown day).
    private func zoneLabels(_ zone: TimeZone) -> some View {
        let day = cal.days.first.map { cal.date(of: $0) } ?? .now
        let shift = (zone.secondsFromGMT(for: day) - cal.calendar.timeZone.secondsFromGMT(for: day)) / 60
        return VStack(spacing: 0) {
            ForEach(0..<24, id: \.self) { h in
                let m = ((h * 60 + shift) % 1440 + 1440) % 1440
                Text(h == 0 ? "" : String(format: "%02d:%02d", m / 60, m % 60))
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .frame(width: Theme.Calendar.timeGutter - Theme.Space.s, height: cal.hourHeight, alignment: .topTrailing)
                    .offset(y: -Theme.Calendar.labelNudge)
                    .padding(.trailing, Theme.Space.s)
            }
        }
    }

    @AppStorage("calendar.weekNumbers") private var weekNumbers = false

    private var header: some View {
        HStack(spacing: 0) {
            Group {
                if weekNumbers, let first = cal.days.first {
                    Text("W\(cal.calendar.component(.weekOfYear, from: cal.date(of: first)))").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                } else {
                    Color.clear
                }
            }
            .frame(width: Theme.Calendar.timeGutter)
            if let zone = extraZone {
                Text(zone.abbreviation(for: cal.days.first.map { cal.date(of: $0) } ?? .now) ?? zone.identifier)
                    .font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    .frame(width: Theme.Calendar.timeGutter)
            }
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
                .frame(width: gutter - Theme.Space.xs, alignment: .trailing)
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
        @Bindable var cal = cal
        let snap = Theme.Calendar.snapMinutes
        let range = cal.newRange?.dayStart == day.dayStart ? cal.newRange : nil
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
                        guard !cal.rangeCancelled, !cal.composingRange else { return }
                        let a = minute(at: v.startLocation.y), b = minute(at: v.location.y)
                        let lo = min(a, b), hi = min(max(max(a, b), lo + snap), 24 * 60)
                        let next = CalendarModel.NewRange(dayStart: day.dayStart, start: lo, end: hi)
                        if cal.newRange != next { cal.newRange = next }
                    }
                    .onEnded { v in
                        if cal.rangeCancelled { cal.rangeCancelled = false; return }
                        // Make sure the final range is in place (a fast drag can end before the last onChanged).
                        let a = minute(at: v.startLocation.y), b = minute(at: v.location.y)
                        let lo = min(a, b), hi = min(max(max(a, b), lo + snap), 24 * 60)
                        cal.newRange = CalendarModel.NewRange(dayStart: day.dayStart, start: lo, end: hi)
                        cal.composingRange = true
                    },
            )
            GeometryReader { geo in
                ForEach(day.timed, id: \.entry.id) { placed in
                    let width = geo.size.width / CGFloat(max(placed.columns, 1))
                    let gap = Theme.Calendar.blockGap
                    let height = max(CGFloat(placed.endMinute - placed.startMinute) / 60 * cal.hourHeight - gap, Theme.Calendar.minBlock)
                    EntryBlock(entry: placed.entry, height: height, width: width - gap)
                        .frame(width: max(width - gap, 0), height: height)
                        .offset(x: CGFloat(placed.column) * width + gap / 2, y: CGFloat(placed.startMinute) / 60 * cal.hourHeight + gap / 2)
                }
            }
            if cal.calendar.isDateInToday(cal.date(of: day)) { NowLine(hourHeight: cal.hourHeight) }
            if let hover = cal.dropHover, hover.dayStart == day.dayStart {
                GhostBlock(start: hover.minute, end: hover.minute + Theme.Calendar.taskMinutes, dayStart: cal.date(of: day), hourHeight: cal.hourHeight)
                    .padding(.top, GhostBlock.top(hover.minute, cal.hourHeight))
                    .allowsHitTesting(false)
            }
            if let r = range {
                // The popover hangs on the block itself (placed with padding, not offset, so its frame is where it's drawn).
                GhostBlock(start: r.start, end: r.end, dayStart: cal.date(of: day), hourHeight: cal.hourHeight)
                    .popover(isPresented: Binding(get: { cal.composingRange && cal.newRange == r }, set: { if !$0 { cal.composingRange = false } }), arrowEdge: .trailing) {
                        TaskComposer(start: .span(time(r.start), time(r.end))) { cal.composingRange = false }
                    }
                    .padding(.top, GhostBlock.top(r.start, cal.hourHeight))
            }
        }
        .frame(height: 24 * cal.hourHeight)
        .overlay(alignment: .leading) { Divider() }
        .onDrop(of: [.plainText], delegate: ColumnDrop(model: cal, dayStart: day.dayStart, hourHeight: cal.hourHeight))
        .onChange(of: cal.composingRange) { _, open in if !open, range != nil { withMotion { cal.newRange = nil } } }
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
    }

    /// Distance from the top of the day column.
    static func top(_ minute: Int, _ hourHeight: CGFloat) -> CGFloat { CGFloat(minute) / 60 * hourHeight + 1 }
}

/// Drop target for a day column: tracks the snapped minute under the pointer (for the placeholder) and
/// schedules the dropped task ids there.
private struct ColumnDrop: DropDelegate {
    let model: CalendarModel
    let dayStart: Int64
    let hourHeight: CGFloat

    private func show(_ m: Int?) {
        let next = m.map { CalendarModel.DropHover(dayStart: dayStart, minute: $0) }
        if model.dropHover != next { withMotion { model.dropHover = next } }
    }

    private func snapped(_ info: DropInfo) -> Int {
        let snap = Theme.Calendar.snapMinutes
        // Centre the default-length block on the pointer.
        let raw = Int(info.location.y / hourHeight * 60) - Theme.Calendar.taskMinutes / 2
        return max(0, min(24 * 60 - snap, Int((Double(raw) / Double(snap)).rounded()) * snap))
    }

    func validateDrop(info: DropInfo) -> Bool { info.hasItemsConforming(to: [.plainText]) }

    func dropEntered(info: DropInfo) { show(snapped(info)) }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        show(snapped(info))
        return DropProposal(operation: .move)
    }

    func dropExited(info: DropInfo) {
        if model.dropHover?.dayStart == dayStart { show(nil) }
    }

    func performDrop(info: DropInfo) -> Bool {
        let m = snapped(info)
        let model = self.model, dayStart = self.dayStart
        show(nil)
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
    @AppStorage("calendar.weekNumbers") private var weekNumbers = false

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
                            if weekNumbers, cal.days.indices.contains(r * 7) {
                                Text("\(cal.calendar.component(.weekOfYear, from: cal.date(of: cal.days[r * 7])))")
                                    .font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                                    .frame(width: Theme.Calendar.weekNumber)
                            }
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

extension CalendarEntry {
    /// Tasks and the things that stand for one (a later repeat, a dated checklist item).
    var isTaskLike: Bool { kind == "task" || kind == "repeat" || kind == "checklist" }

    /// The task a click opens: the task itself, or the one a repeat/checklist/focus entry belongs to.
    var openTaskId: String? { kind == "task" ? id : taskId }

    /// Small kind icon (View options → Show item icons); plain tasks have none.
    var kindIcon: String? {
        switch kind {
        case "event": "calendar"
        case "habit": "target"
        case "countdown": "hourglass"
        case "focus": "timer"
        case "repeat": "repeat"
        case "checklist": "checklist"
        default: nil
        }
    }
}

/// Tasks take their priority (or list) colour, grey for none; events their calendar's; focus a fixed violet.
func entryColor(_ entry: CalendarEntry) -> Color {
    if entry.isTaskLike {
        if let list = Color(hex: entry.color) { return list } // coloured by list
        return entry.priority == 0 ? Color.secondary : Theme.PriorityColor.color(Int32(entry.priority))
    }
    if entry.kind == "focus" { return Color(hex: Theme.Calendar.focusColor) ?? .purple }
    return Color(hex: entry.color) ?? .themeAccent
}

/// Fill, text and edge of an entry in the chosen style. Classic: solid with white text. Modern: a tint with a
/// coloured edge and normal text. Done items fade.
private struct EntryLook: ViewModifier {
    let entry: CalendarEntry
    let modern: Bool

    func body(content: Content) -> some View {
        let color = entryColor(entry)
        let shape = RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous)
        content
            .foregroundStyle(modern ? AnyShapeStyle(.primary) : AnyShapeStyle(Color.white))
            .background(color.opacity(modern ? Theme.Calendar.modernFill : Theme.Calendar.classicFill), in: shape)
            .overlay(alignment: .leading) { if modern { RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 3) } }
            .clipShape(shape)
            .opacity(entry.done || entry.kind == "repeat" ? 0.6 : 1)
    }
}

/// A timed block in the Week/Day grid. Short blocks put title and time on one line, so stacked blocks
/// stay readable. Holding ⌥ shows a task's checkbox, as in TickTick.
private struct EntryBlock: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(CalendarModel.self) private var cal
    let entry: CalendarEntry
    let height: CGFloat
    let width: CGFloat
    @State private var showing = false

    var body: some View {
        let o = cal.viewOptions
        let compact = height < Theme.Calendar.compactBlock
        Group {
            if compact {
                // Title and time on one line: the title truncates first; the time drops only when there's no room.
                HStack(spacing: Theme.Space.xs) {
                    lead(o)
                    TitleAndTime {
                        title(.caption.weight(.semibold)).lineLimit(1)
                        Text(time).font(.caption2).opacity(0.8).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 1)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .firstTextBaseline, spacing: Theme.Space.xs) {
                        lead(o)
                        // Narrow columns get one line: wrapping there breaks words in the middle.
                        title(.callout.weight(.medium)).lineLimit(width < Theme.Calendar.wrapWidth ? 1 : max(1, Int((height - 16) / 16)))
                    }
                    Text(time).font(.caption).opacity(0.85).lineLimit(1)
                }
                .padding(.vertical, Theme.Space.xxs)
            }
        }
        .padding(.leading, Theme.Space.xs + Theme.Space.xxs + (o.isModern ? 2 : 0))
        .padding(.trailing, Theme.Space.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .modifier(EntryLook(entry: entry, modern: o.isModern))
        .contentShape(Rectangle())
        .onTapGesture { showing = true }
        .popover(isPresented: $showing) { EntryDetail(entry: entry) }
        .modifier(DraggableTask(entry: entry))
        .contextMenu { if entry.kind == "task", let t = tasks.index?.task(id: entry.id) { TaskMenu(task: t) } }
        .help(entry.title + " · " + time)
    }

    private func title(_ font: Font) -> some View {
        Text(entry.title).font(font).strikethrough(entry.done)
    }

    @ViewBuilder private func lead(_ o: CalendarViewOptions) -> some View {
        if entry.kind == "task" && cal.optionDown {
            TaskCheck(entry: entry)
        } else if o.showIcons, width >= Theme.Calendar.iconMinWidth, let icon = entry.kindIcon {
            Image(systemName: icon).font(.caption2).opacity(0.85)
        }
    }

    private var time: String {
        Date(timeIntervalSince1970: Double(entry.start) / 1000).formatted(date: .omitted, time: .shortened)
    }
}

/// Lays out [title, time] on one line. Both at their natural width when they fit; otherwise the title takes what's
/// left after the time; when even a short title wouldn't fit, the time is hidden.
private struct TitleAndTime: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { Self.natural($0) }
        let natural = sizes.reduce(0) { $0 + $1.width } + Theme.Space.xs * CGFloat(max(0, sizes.count - 1))
        return CGSize(width: min(proposal.width ?? natural, natural), height: sizes.map(\.height).max() ?? 0)
    }

    /// Natural size, rounded up: text placed at exactly its fractional width truncates.
    private static func natural(_ view: LayoutSubview) -> CGSize {
        let s = view.sizeThatFits(.unspecified)
        return CGSize(width: s.width.rounded(.up) + 1, height: s.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let title = Self.natural(subviews[0]), time = Self.natural(subviews[1])
        let gap = Theme.Space.xs
        let room = bounds.width - time.width - gap
        let showTime = room >= min(title.width, Theme.Calendar.minTitle)
        let titleWidth = showTime ? min(title.width, room) : bounds.width
        subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(width: titleWidth, height: bounds.height))
        if showTime {
            subviews[1].place(at: CGPoint(x: bounds.minX + titleWidth + gap, y: bounds.midY), anchor: .leading, proposal: .unspecified)
        } else {
            subviews[1].place(at: CGPoint(x: bounds.maxX + 1000, y: bounds.midY), anchor: .leading, proposal: .zero)
        }
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
        let o = cal.viewOptions
        HStack(spacing: Theme.Space.xs) {
            if entry.kind == "task" && cal.optionDown {
                TaskCheck(entry: entry)
            } else if o.showIcons, let icon = entry.kindIcon {
                Image(systemName: icon).font(.caption2).opacity(0.85)
            }
            Text(entry.title).lineLimit(1).strikethrough(entry.done)
            Spacer(minLength: 0)
        }
        .font(.caption.weight(.medium))
        .padding(.leading, Theme.Space.xs + Theme.Space.xxs + (o.isModern ? 2 : 0))
        .padding(.trailing, Theme.Space.xs)
        .padding(.vertical, Theme.Space.xxs)
        .modifier(EntryLook(entry: entry, modern: o.isModern))
        .contentShape(Rectangle())
        .onTapGesture { showing = true }
        .popover(isPresented: $showing) { EntryDetail(entry: entry) }
        .modifier(DraggableTask(entry: entry))
        .contextMenu { if entry.kind == "task", let t = tasks.index?.task(id: entry.id) { TaskMenu(task: t) } }
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
/// The view picker: each mode with its key; Multi-Day and Multi-Week have − / + for how many days or weeks.
private struct ModePicker: View {
    @Environment(CalendarModel.self) private var cal
    let done: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xxs) {
            ForEach(CalendarModel.Mode.allCases) { mode in
                if mode == .multiDay { Divider().padding(.vertical, Theme.Space.xs) }
                HStack(spacing: Theme.Space.s) {
                    Button { cal.mode = mode; done() } label: {
                        HStack {
                            Image(systemName: "checkmark").opacity(cal.mode == mode ? 1 : 0).foregroundStyle(.tint)
                            Text(mode.rawValue)
                            Spacer(minLength: Theme.Space.l)
                            if let keys = mode.keys {
                                Text("\(String(keys.letter).uppercased()) / \(String(keys.number))").font(.callout.monospaced()).foregroundStyle(.secondary)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if mode == .multiDay {
                        Stepper(value: Binding(get: { cal.multiDays }, set: { cal.multiDays = $0; cal.mode = .multiDay }), in: 2...14) {
                            Text("\(cal.multiDays) days").monospacedDigit().foregroundStyle(.secondary)
                        }
                        .fixedSize()
                    } else if mode == .multiWeek {
                        Stepper(value: Binding(get: { cal.multiWeeks }, set: { cal.multiWeeks = $0; cal.mode = .multiWeek }), in: 2...6) {
                            Text("\(cal.multiWeeks) weeks").monospacedDigit().foregroundStyle(.secondary)
                        }
                        .fixedSize()
                    }
                }
                .frame(minHeight: Theme.List.pickerRow)
            }
        }
        .padding(Theme.Space.m)
        .frame(width: Theme.Calendar.modePickerWidth)
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
                Button("") { cal.mode = mode }
                    .keyboardShortcut(KeyEquivalent(mode.keys!.letter), modifiers: [])
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
        if entry.kind != "focus", let id = entry.openTaskId, tasks.index?.task(id: id) != nil {
            TaskQuickEditor(taskId: id)
        } else {
            info
        }
    }

    private var info: some View {
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
            } else if entry.kind == "focus" {
                Label("A focus session", systemImage: "timer").font(.caption).foregroundStyle(.secondary)
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

    /// Heatmap shade: busier days are darker (1 item light, 5+ strongest).
    static func heat(_ count: Int) -> Double { Theme.Calendar.heatMin + Double(min(count, 5) - 1) * Theme.Calendar.heatStep }

    var body: some View {
        let first = cal.calendar.date(from: DateComponents(year: year, month: month, day: 1))!
        let gridStart = cal.calendar.dateInterval(of: .weekOfYear, for: first)?.start ?? first
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(first.formatted(.dateTime.month(.wide))).font(.headline)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(Theme.Calendar.yearCell), spacing: 2), count: 7), spacing: 2) {
                ForEach(0..<42, id: \.self) { i in
                    let date = cal.calendar.date(byAdding: .day, value: i, to: gridStart)!
                    let inMonth = cal.calendar.component(.month, from: date) == month
                    let count = inMonth && cal.viewOptions.yearHeatmap ? (counts[date] ?? 0) : 0
                    Button {
                        cal.anchor = date
                        cal.mode = .day
                    } label: {
                        Text(date.formatted(.dateTime.day()))
                            .font(.caption)
                            .frame(width: Theme.Calendar.yearCell, height: Theme.Calendar.yearCell)
                            .foregroundStyle(cal.calendar.isDateInToday(date) ? Color.white : (inMonth ? Color.primary : Color.secondary.opacity(0.4)))
                            .background(cal.calendar.isDateInToday(date) ? AnyShapeStyle(.tint) : (count > 0 ? AnyShapeStyle(.tint.opacity(Self.heat(count))) : AnyShapeStyle(.clear)),
                                        in: RoundedRectangle(cornerRadius: Theme.Calendar.heatRadius, style: .continuous))
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

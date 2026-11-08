import SwiftUI
@preconcurrency import ZenTaskShared

/// The Calendar area: Day / Week / Month with tasks and read-only events, plus an unscheduled-task panel.
struct CalendarView: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        @Bindable var cal = cal
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                toolbar
                Divider()
                switch cal.mode {
                case .month: MonthGrid()
                default: WeekGrid()
                }
            }
            Divider()
            UnscheduledPanel()
                .frame(width: Theme.Calendar.panelWidth)
        }
        .onAppear { cal.start(); tasks.start() }
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
            Picker("View", selection: $cal.mode) {
                ForEach(CalendarModel.Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 220)
        }
        .controlSize(.large)
        .padding(.horizontal, Theme.Space.l)
        .padding(.vertical, Theme.Space.m)
    }
}

// MARK: - Week / Day

private struct WeekGrid: View {
    @Environment(CalendarModel.self) private var cal

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
                    .overlay(alignment: .topLeading) { Color.clear.frame(height: 1).offset(y: 7 * Theme.Calendar.hourHeight).id("seven") }
                }
                .onAppear { proxy.scrollTo("seven", anchor: .top) }
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
                    ids.forEach { cal.schedule(taskId: $0, on: day, minute: nil) }
                    return !ids.isEmpty
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
                    .frame(width: Theme.Calendar.timeGutter - Theme.Space.s, height: Theme.Calendar.hourHeight, alignment: .topTrailing)
                    .offset(y: -7)
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
                        Rectangle().fill(.clear).frame(height: Theme.Calendar.hourHeight)
                            .overlay(alignment: .top) { Divider() }
                    }
                }
                ForEach(day.timed, id: \.entry.id) { placed in
                    let width = geo.size.width / CGFloat(max(placed.columns, 1))
                    EntryBlock(entry: placed.entry)
                        .frame(width: max(width - 2, 0), height: max(CGFloat(placed.endMinute - placed.startMinute) / 60 * Theme.Calendar.hourHeight - 2, 18))
                        .offset(x: CGFloat(placed.column) * width + 1, y: CGFloat(placed.startMinute) / 60 * Theme.Calendar.hourHeight + 1)
                }
                if cal.calendar.isDateInToday(cal.date(of: day)) { NowLine() }
            }
            .dropDestination(for: String.self) { ids, location in
                let minute = Int(location.y / Theme.Calendar.hourHeight * 60)
                ids.forEach { cal.schedule(taskId: $0, on: day, minute: minute) }
                return !ids.isEmpty
            }
        }
        .frame(height: 24 * Theme.Calendar.hourHeight)
        .overlay(alignment: .leading) { Divider() }
    }
}

private struct NowLine: View {
    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let c = Calendar.current.dateComponents([.hour, .minute], from: context.date)
            let y = CGFloat((c.hour ?? 0) * 60 + (c.minute ?? 0)) / 60 * Theme.Calendar.hourHeight
            HStack(spacing: 0) {
                Circle().fill(.red).frame(width: 8, height: 8)
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
        let month = cal.calendar.component(.month, from: cal.anchor)
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
                                    MonthCell(day: cal.days[i], inMonth: cal.calendar.component(.month, from: cal.date(of: cal.days[i])) == month)
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
            ids.forEach { cal.schedule(taskId: $0, on: day, minute: nil) }
            return !ids.isEmpty
        }
    }
}

// MARK: - Entries

private func entryColor(_ entry: CalendarEntry) -> Color {
    if entry.kind == "task" { return Theme.PriorityColor.color(Int32(entry.priority)) == .secondary ? .accentColor : Theme.PriorityColor.color(Int32(entry.priority)) }
    return Color(hex: entry.color) ?? .accentColor
}

/// A timed block in the Week/Day grid.
private struct EntryBlock: View {
    @Environment(TasksModel.self) private var tasks
    let entry: CalendarEntry
    @State private var showing = false

    var body: some View {
        let color = entryColor(entry)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.Space.xs) {
                if entry.kind == "task" {
                    Image(systemName: entry.done ? "checkmark.square.fill" : "square").foregroundStyle(color)
                }
                Text(entry.title).font(.callout.weight(.medium)).strikethrough(entry.done).lineLimit(2)
            }
            Text(Date(timeIntervalSince1970: Double(entry.start) / 1000).formatted(date: .omitted, time: .shortened))
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.horizontal, Theme.Space.xs + Theme.Space.xxs)
        .padding(.vertical, Theme.Space.xxs)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(color.opacity(Theme.Calendar.eventFill), in: RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
        .overlay(alignment: .leading) { RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 3) }
        .clipShape(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
        .opacity(entry.done ? 0.6 : 1)
        .contentShape(Rectangle())
        .onTapGesture { showing = true }
        .popover(isPresented: $showing) { EntryDetail(entry: entry) }
        .draggable(entry.kind == "task" ? entry.id : "")
    }
}

/// A one-line chip (all-day row, month cells).
private struct EntryChip: View {
    let entry: CalendarEntry
    let compact: Bool
    @State private var showing = false

    var body: some View {
        let color = entryColor(entry)
        HStack(spacing: Theme.Space.xs) {
            if entry.kind == "task" {
                Image(systemName: entry.done ? "checkmark.square.fill" : "square").foregroundStyle(color)
            } else {
                Circle().fill(color).frame(width: 7, height: 7)
            }
            Text(entry.title).lineLimit(1).strikethrough(entry.done)
            Spacer(minLength: 0)
        }
        .font(.caption.weight(.medium))
        .padding(.horizontal, Theme.Space.xs + Theme.Space.xxs)
        .padding(.vertical, Theme.Space.xxs)
        .background(color.opacity(Theme.Calendar.eventFill), in: RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { showing = true }
        .popover(isPresented: $showing) { EntryDetail(entry: entry) }
        .draggable(entry.kind == "task" ? entry.id : "")
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
                    Button("Remove time") { tasks.core.actions.setDue(id: entry.id, due: nil, allDay: false) }
                }
                .controlSize(.large)
            } else {
                Label("From your calendar (read-only)", systemImage: "calendar").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(Theme.Space.l)
        .frame(width: 300, alignment: .leading)
    }

    private var timeText: String {
        let s = Date(timeIntervalSince1970: Double(entry.start) / 1000)
        let e = Date(timeIntervalSince1970: Double(entry.end) / 1000)
        if entry.allDay { return s.formatted(date: .abbreviated, time: .omitted) + " · all day" }
        return s.formatted(date: .abbreviated, time: .shortened) + " – " + e.formatted(date: .omitted, time: .shortened)
    }
}

// MARK: - Unscheduled panel

/// Open tasks without a date, to drag onto the calendar.
private struct UnscheduledPanel: View {
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        let rows = (tasks.index?.allTasks ?? []).filter { $0.isOpen && $0.trashedAt == nil && $0.startDate == nil && $0.dueDate == nil && $0.parentId == nil }
        VStack(alignment: .leading, spacing: 0) {
            Text("Unscheduled").font(.headline).padding(Theme.Space.l)
            Text("Drag a task onto the calendar to give it a time").font(.caption).foregroundStyle(.secondary)
                .padding(.horizontal, Theme.Space.l).padding(.bottom, Theme.Space.s)
            List(rows.prefix(300), id: \.id) { task in
                HStack(spacing: Theme.Space.s) {
                    Image(systemName: "square").foregroundStyle(Theme.PriorityColor.color(task.priority))
                    Text(task.title).lineLimit(2)
                }
                .padding(.vertical, Theme.Space.xxs)
                .draggable(task.id)
            }
            .listStyle(.inset)
        }
    }
}

extension ZTask {
    var isOpen: Bool { status == 0 }
}

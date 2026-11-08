import SwiftUI
@preconcurrency import ZenTaskShared

// Side panels: a narrow calendar beside the Tasks screen, and a task list beside the Calendar.

/// The calendar shown beside the task detail. It has its own CalendarModel so it never moves the main
/// Calendar's date or mode.
@MainActor
@Observable
final class PanelCalendar {
    let cal: CalendarModel

    init(core: ZenTaskCore) {
        cal = CalendarModel(core: core)
        let saved = UserDefaults.standard.string(forKey: "tasks.calendarPanel.mode")
        cal.mode = saved.flatMap(CalendarModel.Mode.init(rawValue:)).flatMap { PanelCalendar.modes.contains($0) ? $0 : nil } ?? .agenda
    }

    /// Modes that fit a narrow column.
    static let modes: [CalendarModel.Mode] = [.day, .agenda, .month]

    func setMode(_ mode: CalendarModel.Mode) {
        cal.mode = mode
        UserDefaults.standard.set(mode.rawValue, forKey: "tasks.calendarPanel.mode")
    }
}

/// "◧ November 2026   ‹ ○ ›  …" over an agenda timeline, a day grid or a month.
struct CalendarPanel: View {
    @Environment(PanelCalendar.self) private var panel
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        let cal = panel.cal
        VStack(spacing: 0) {
            HStack(spacing: Theme.Space.s) {
                Text(cal.anchor.formatted(.dateTime.month(.wide).year()))
                    .font(.title2.weight(.bold))
                    .lineLimit(1)
                Spacer(minLength: Theme.Space.s)
                Button { cal.previous() } label: { Image(systemName: "chevron.left") }.help("Previous")
                Button { cal.today() } label: { Image(systemName: "circle") }.help("Today")
                Button { cal.next() } label: { Image(systemName: "chevron.right") }.help("Next")
                Menu {
                    ForEach(PanelCalendar.modes) { mode in
                        Button { panel.setMode(mode) } label: {
                            if cal.mode == mode { Label(mode.rawValue, systemImage: "checkmark") } else { Text(mode.rawValue) }
                        }
                    }
                    Divider()
                    Button("Close", systemImage: "xmark") {
                        withMotion { tasks.showCalendarPanel = false }
                    }
                } label: {
                    Image(systemName: "ellipsis").frame(width: Theme.List.headerButton, height: Theme.List.headerButton)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("View and close")
            }
            .buttonStyle(.borderless)
            .font(.title3)
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.m)
            Divider()
            Group {
                switch cal.mode {
                case .day: WeekGrid()
                case .month: MonthGrid()
                default: AgendaTimeline()
                }
            }
            .environment(cal)
        }
        .onAppear { cal.start(); cal.setVisible(true) }
        .onDisappear { cal.setVisible(false) }
    }
}

// MARK: - Agenda timeline

/// Days with something on them: a big day number, then each item at its time on a dotted timeline, as a
/// card in its colour. Used by the main Calendar's Agenda mode and the panel.
struct AgendaTimeline: View {
    @Environment(CalendarModel.self) private var cal

    var body: some View {
        let today = cal.calendar.startOfDay(for: .now)
        let days = cal.days.filter { !$0.allDay.isEmpty || !$0.timed.isEmpty || cal.date(of: $0) == today }
        if days.isEmpty {
            ContentUnavailableView("Nothing in the next 30 days", systemImage: "calendar")
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Theme.Space.xl) {
                    ForEach(days, id: \.dayStart) { day in
                        AgendaDay(day: day, isToday: cal.date(of: day) == today)
                    }
                }
                .padding(.horizontal, Theme.Space.l)
                .padding(.vertical, Theme.Space.l)
            }
        }
    }
}

private struct AgendaDay: View {
    @Environment(CalendarModel.self) private var cal
    let day: CalendarDay
    let isToday: Bool

    var body: some View {
        let date = cal.date(of: day)
        let entries = Array(day.allDay) + day.timed.map(\.entry)
        VStack(alignment: .leading, spacing: Theme.Space.s) {
        HStack(alignment: .top, spacing: Theme.Space.m) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.xs) {
                Text("\(cal.calendar.component(.day, from: date))")
                    .font(.system(size: Theme.Panel.agendaDay, weight: .semibold, design: .rounded).monospacedDigit())
                Text(date.formatted(.dateTime.weekday(.abbreviated)))
                    .font(.caption.weight(.medium))
            }
            .foregroundStyle(isToday ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
            .frame(minWidth: Theme.Panel.agendaDay * 1.6, alignment: .leading)
            Spacer(minLength: 0)
        }
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            if entries.isEmpty {
                Text("Nothing planned").font(.callout).foregroundStyle(.secondary).padding(.leading, Theme.Panel.agendaTime + Theme.Space.l)
            }
            ForEach(entries, id: \.id) { entry in
                AgendaRow(entry: entry)
            }
        }
        }
    }
}

private struct AgendaRow: View {
    @Environment(TasksModel.self) private var tasks
    let entry: CalendarEntry
    @State private var showing = false

    var body: some View {
        let color = entryColor(entry)
        let start = Date(timeIntervalSince1970: Double(entry.start) / 1000)
        let end = Date(timeIntervalSince1970: Double(entry.end) / 1000)
        HStack(alignment: .center, spacing: Theme.Space.s) {
            Text(entry.allDay ? "All day" : start.formatted(date: .omitted, time: .shortened))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: Theme.Panel.agendaTime, alignment: .leading)
            Circle().fill(color).frame(width: Theme.Panel.agendaDot, height: Theme.Panel.agendaDot)
            HStack(spacing: 0) {
                Rectangle().fill(color).frame(width: Theme.Panel.agendaBar)
                VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                    if !entry.allDay && entry.kind != "task" {
                        Text("\(start.formatted(date: .omitted, time: .shortened)) – \(end.formatted(date: .omitted, time: .shortened))")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(color)
                    }
                    HStack(spacing: Theme.Space.xs) {
                        if entry.kind == "task" { TaskCheck(entry: entry) }
                        if entry.kind == "countdown" { Image(systemName: "hourglass").font(.caption) }
                        Text(entry.title.isEmpty ? "Untitled" : entry.title)
                            .font(.callout.weight(.medium))
                            .strikethrough(entry.done)
                            .lineLimit(2)
                    }
                }
                .padding(.horizontal, Theme.Space.m)
                .padding(.vertical, Theme.Space.s)
                Spacer(minLength: 0)
            }
            .background(color.opacity(Theme.Calendar.eventFill))
            .clipShape(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous))
            .contentShape(Rectangle())
            .onTapGesture { showing = true }
            .popover(isPresented: $showing) { EntryDetail(entry: entry) }
            .modifier(DraggableTask(entry: entry))
            .contextMenu { if entry.kind == "task", let t = tasks.index?.task(id: entry.id) { TaskMenu(task: t) } }
        }
    }
}

// MARK: - Task panel (beside the Calendar)

/// The task list beside the Calendar: any list or smart list, with its own grouping and sorting
/// (remembered on this Mac), to drag tasks onto the calendar.
@MainActor
@Observable
final class TaskPanelState {
    var item: SidebarItem {
        didSet { UserDefaults.standard.set(item.storageKey, forKey: "calendar.panel.item") }
    }
    var options: ViewOptions {
        didSet { UserDefaults.standard.set(options.encode(), forKey: "calendar.panel.options") }
    }
    var onlyUndated: Bool {
        didSet { UserDefaults.standard.set(onlyUndated, forKey: "calendar.panel.undated") }
    }
    private(set) var sections: [TaskSection] = []

    init() {
        let d = UserDefaults.standard
        item = d.string(forKey: "calendar.panel.item").flatMap(SidebarItem.init(storageKey:)) ?? .smart(.all)
        options = d.string(forKey: "calendar.panel.options").map { ViewOptions.companion.parse(json: $0) }
            ?? ViewOptions(sort: "", group: "list", showCompleted: false, order: "", hideCompleted: true, hideDetails: false)
        onlyUndated = d.object(forKey: "calendar.panel.undated") as? Bool ?? false
    }

    func recompute(_ tasks: TasksModel) async {
        guard let index = tasks.index else { return }
        let core = tasks.core, filter = item.filter, opts = options, undated = onlyUndated
        let result = await _Concurrency.Task.detached(priority: .userInitiated) { () -> [TaskSection] in
            let sections = core.sections(index: index, filter: filter, options: opts)
            guard undated else { return sections }
            return sections.compactMap { s in
                let rows = s.rows.filter { $0.task.startDate == nil && $0.task.dueDate == nil }
                return rows.isEmpty ? nil : s.doCopy(title: s.title, rows: rows, isCompleted: s.isCompleted, columnId: s.columnId, key: s.key, color: s.color)
            }
        }.value
        sections = result
    }
}

struct TaskPanel: View {
    @Environment(TasksModel.self) private var tasks
    /// Created once on first appearance (an inline initial value would rebuild it on every parent render).
    @State private var holder: TaskPanelState?
    let close: () -> Void

    var body: some View {
        Group {
            if let holder { TaskPanelContent(state: holder, close: close) } else { Color.clear }
        }
        .onAppear { if holder == nil { holder = TaskPanelState() } }
    }
}

private struct TaskPanelContent: View {
    @Environment(TasksModel.self) private var tasks
    @Bindable var state: TaskPanelState
    let close: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ListHeader(item: $state.item, options: state.options, setOptions: { state.options = $0 }) {
                Divider()
                Toggle("Only tasks without a date", systemImage: "calendar.badge.minus", isOn: $state.onlyUndated)
                Divider()
                Button("Close", systemImage: "xmark", action: close)
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.vertical, Theme.Space.m)
            Text("Drag a task onto the calendar to give it a time")
                .font(.caption).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Theme.Space.l)
                .padding(.bottom, Theme.Space.s)
            List {
                ForEach(state.sections, id: \.key) { section in
                    Section {
                        let scope = "panel:" + state.item.storageKey
                        let folded = tasks.isCollapsed(section, in: scope)
                        if !section.title.isEmpty {
                            GroupHeaderRow(title: section.title, count: section.rows.count, collapsed: folded) { tasks.toggleCollapsed(section, in: scope) }
                        }
                        if !folded || section.title.isEmpty {
                            ForEach(section.rows.prefix(300), id: \.task.id) { row in
                                PanelTaskRow(task: row.task, details: !state.options.hideDetails)
                                    .listRowBackground(Color(hex: section.color).map { GroupBar(color: $0) })
                            }
                        }
                    }
                }
            }
            .listStyle(.inset)
            .overlay {
                if state.sections.isEmpty { ContentUnavailableView("No tasks here", systemImage: "checkmark.circle") }
            }
        }
        .task(id: RecomputeKey(index: tasks.index.map(ObjectIdentifier.init), item: state.item.storageKey, options: state.options.encode(), undated: state.onlyUndated)) {
            await state.recompute(tasks)
        }
        .onAppear { tasks.start() }
    }

    private struct RecomputeKey: Equatable {
        let index: ObjectIdentifier?
        let item: String
        let options: String
        let undated: Bool
    }
}

/// A compact, draggable task line for the panel: checkbox, title, notes preview, date.
private struct PanelTaskRow: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask
    let details: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
            CheckButton(task: task) { tasks.toggleComplete(task) }
            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                Text(task.title.isEmpty ? "Untitled" : task.title).lineLimit(2).strikethrough(task.isDone)
                if details, let preview = TaskRowView.preview(task) {
                    Text(preview).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let day = tasks.day(of: task) {
                Text(DateText.short(day: day, task: task)).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, Theme.Space.xxs)
        .padding(.leading, Theme.Space.xs)
        .contentShape(Rectangle())
        .draggable(task.id)
        .contextMenu { TaskMenu(task: task) }
    }
}

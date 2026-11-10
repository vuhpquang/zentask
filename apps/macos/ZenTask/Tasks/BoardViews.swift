import SwiftUI
@preconcurrency import ZenTaskShared

/// Kanban: the list's groups (sections, priorities, lists, dates…) as columns of cards. Drag a card to another
/// column to move it there (section, priority or list); "+ Add task" at the bottom adds to that column.
struct KanbanBoard: View {
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: Theme.Space.m) {
                ForEach(tasks.sections.filter { !$0.isCompleted }, id: \.key) { section in
                    KanbanColumn(section: section)
                }
            }
            .padding(Theme.Space.l)
        }
    }
}

private struct KanbanColumn: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.themeColors) private var themeColors
    let section: TaskSection
    @State private var newTitle = ""
    @State private var targeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.s) {
                if let c = Color(hex: section.color) { Circle().fill(c).frame(width: Theme.Calendar.dot, height: Theme.Calendar.dot) }
                Text(section.title.isEmpty ? "Tasks" : section.title).font(.headline).lineLimit(1)
                Text("\(section.rows.count)").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, Theme.Space.xs)
            ScrollView {
                LazyVStack(spacing: Theme.Space.s) {
                    ForEach(section.rows, id: \.task.id) { row in
                        KanbanCard(row: row)
                    }
                    if canAdd {
                        HStack(spacing: Theme.Space.s) {
                            Image(systemName: "plus").foregroundStyle(.secondary)
                            TextField("Add task", text: $newTitle).textFieldStyle(.plain).onSubmit(add)
                        }
                        .padding(Theme.Space.s)
                    }
                }
            }
        }
        .padding(Theme.Space.s)
        .frame(width: Theme.Board.columnWidth)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
            .fill(targeted ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.quaternary.opacity(Theme.Opacity.card))))
        .dropDestination(for: String.self) { ids, _ in tasks.drop(ids, into: section) } isTargeted: { targeted = $0 }
    }

    /// Adding works where the column says where the task goes (a list's section, a priority, a list).
    private var canAdd: Bool { section.columnId != nil || section.key == "col:none" || section.key == "all" || section.key.hasPrefix("prio:") || section.key.hasPrefix("list:") }

    private func add() {
        let title = newTitle.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        let project: String? = { if case .project(let id) = tasks.sidebar { return id }; return section.key.hasPrefix("list:") ? String(section.key.dropFirst(5)) : nil }()
        let prio: KotlinInt? = section.key.hasPrefix("prio:") ? Int32(section.key.dropFirst(5)).map { KotlinInt(int: $0) } : nil
        let task = tasks.core.actions.create(title: title, filter: tasks.sidebar.filter, projectId: project, priority: prio, tags: [], due: nil, allDay: true)
        if section.columnId != nil { tasks.drop([task.id], intoColumn: section.columnId) }
        newTitle = ""
    }
}

private struct KanbanCard: View {
    @Environment(TasksModel.self) private var tasks
    let row: TaskRow

    var body: some View {
        let selected = tasks.selection == row.task.id
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
                CheckButton(task: row.task) { tasks.toggleComplete(row.task) }
                Text(row.task.title.isEmpty ? "Untitled" : row.task.title).lineLimit(3)
                Spacer(minLength: 0)
            }
            if let preview = TaskRowView.preview(row.task), !tasks.options.hideDetails {
                Text(preview).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            HStack(spacing: Theme.Space.s) {
                if let day = tasks.day(of: row.task) {
                    Label(DateText.short(day: day, task: row.task), systemImage: "calendar")
                        .foregroundStyle(row.overdue ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                }
                ForEach(row.task.tags.prefix(2), id: \.self) { TagPill(name: $0, color: tasks.tagColor($0)) }
                if row.checklistTotal > 0 { Label("\(row.checklistDone)/\(row.checklistTotal)", systemImage: "checklist").foregroundStyle(.secondary) }
            }
            .font(.caption)
            .labelStyle(.titleAndIcon)
        }
        .padding(Theme.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous).fill(.background))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous)
            .strokeBorder(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary), lineWidth: selected ? 2 : 1))
        .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.tile, style: .continuous))
        .onTapGesture { tasks.selection = row.task.id }
        .draggable(row.task.id)
        .contextMenu { TaskMenu(task: row.task) }
    }
}

/// Timeline: dated tasks as bars on a day scale (a week back, four weeks ahead), grouped like the list.
/// Click a bar to open the task.
struct TimelineBoard: View {
    @Environment(TasksModel.self) private var tasks
    private let back = 7
    private let ahead = 28

    var body: some View {
        let cal = Calendar.current
        let today = cal.startOfDay(for: .now)
        let first = cal.date(byAdding: .day, value: -back, to: today) ?? today
        let days = (0..<(back + ahead)).compactMap { cal.date(byAdding: .day, value: $0, to: first) }
        let w = Theme.Board.dayWidth
        ScrollView([.horizontal, .vertical]) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    ForEach(days, id: \.self) { d in
                        VStack(spacing: Theme.Space.xxs) {
                            Text(d.formatted(.dateTime.weekday(.narrow))).font(.caption2).foregroundStyle(.secondary)
                            Text(d.formatted(.dateTime.day())).font(.caption.weight(cal.isDateInToday(d) ? .bold : .regular))
                                .foregroundStyle(cal.isDateInToday(d) ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                        }
                        .frame(width: w)
                    }
                }
                .padding(.vertical, Theme.Space.s)
                Divider()
                ForEach(tasks.sections.filter { !$0.isCompleted }, id: \.key) { section in
                    let dated = section.rows.filter { span($0.task, first: first, count: days.count) != nil }
                    if !dated.isEmpty {
                        Text(section.title.isEmpty ? "Tasks" : section.title).font(.headline).padding(.top, Theme.Space.m).padding(.leading, Theme.Space.s)
                        ForEach(dated, id: \.task.id) { row in
                            let s = span(row.task, first: first, count: days.count)!
                            ZStack(alignment: .leading) {
                                Color.clear.frame(width: w * CGFloat(days.count), height: Theme.Board.rowHeight)
                                let wide = s.length >= Theme.Board.titleInsideDays
                                HStack(spacing: Theme.Space.s) {
                                    Text(wide ? row.task.title : "").font(.caption.weight(.medium)).lineLimit(1)
                                        .padding(.horizontal, Theme.Space.s)
                                        .frame(width: max(w * CGFloat(s.length), w), height: Theme.Board.barHeight, alignment: .leading)
                                        .background(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous)
                                            .fill(Theme.PriorityColor.color(row.task.priority).opacity(Theme.Calendar.eventFill + 0.1)))
                                        .overlay(RoundedRectangle(cornerRadius: Theme.Calendar.eventRadius, style: .continuous)
                                            .strokeBorder(tasks.selection == row.task.id ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear), lineWidth: 2))
                                    // A short bar can't hold its title: show it beside the bar.
                                    if !wide { Text(row.task.title).font(.caption).lineLimit(1).fixedSize() }
                                }
                                .offset(x: w * CGFloat(s.start))
                                .contentShape(Rectangle())
                                .onTapGesture { tasks.selection = row.task.id }
                                .contextMenu { TaskMenu(task: row.task) }
                                .help(row.task.title)
                            }
                        }
                    }
                }
            }
            .overlay(alignment: .topLeading) {
                Rectangle().fill(.tint).frame(width: 2).offset(x: w * CGFloat(back) + w / 2).allowsHitTesting(false)
            }
            .padding(Theme.Space.l)
        }
        .overlay {
            if tasks.sections.allSatisfy({ $0.rows.allSatisfy { tasks.day(of: $0.task) == nil } }) {
                ContentUnavailableView("No dated tasks", systemImage: "calendar.day.timeline.left", description: Text("Give tasks a date to see them on the timeline"))
            }
        }
    }

    /// Day index and length (in days) of a task within the window, or nil when it falls outside.
    private func span(_ t: ZTask, first: Date, count: Int) -> (start: Int, length: Int)? {
        let cal = Calendar.current
        guard let startMs = t.startDate ?? t.dueDate else { return nil }
        let s = cal.startOfDay(for: Date(timeIntervalSince1970: Double(startMs.int64Value) / 1000))
        var e = cal.startOfDay(for: Date(timeIntervalSince1970: Double((t.dueDate ?? startMs).int64Value) / 1000))
        // An all-day range ends at the midnight after its last day.
        if t.isAllDay, t.startDate != nil, e > s { e = cal.date(byAdding: .day, value: -1, to: e) ?? e }
        let a = cal.dateComponents([.day], from: first, to: s).day ?? 0
        let b = max(a, cal.dateComponents([.day], from: first, to: e).day ?? a)
        guard b >= 0, a < count else { return nil }
        let lo = max(0, a), hi = min(count - 1, b)
        return (lo, hi - lo + 1)
    }
}

/// Summary: what got done and what's open in a period, by list, with a Copy button for reports.
struct SummaryView: View {
    @Environment(TasksModel.self) private var tasks
    @State private var period = "week"
    @State private var summary: Summary?
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "doc.text.magnifyingglass").font(.title3).foregroundStyle(.tint)
                Text("Summary").font(.title2.weight(.bold))
                Spacer()
                Button(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    guard let text = summary?.text() else { return }
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                    copied = true
                }
                .disabled(summary == nil)
            }
            .padding(.horizontal, Theme.Space.l)
            .padding(.top, Theme.Space.m)
            Picker("Period", selection: $period) {
                Text("Today").tag("today")
                Text("This week").tag("week")
                Text("Last week").tag("lastweek")
                Text("This month").tag("month")
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(Theme.Space.l)
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.l) {
                    if let s = summary {
                        block("Done", s.done, done: true)
                        block("Open", s.open, done: false)
                        if s.done.isEmpty && s.open.isEmpty {
                            ContentUnavailableView("Nothing in this period", systemImage: "tray")
                        }
                    }
                }
                .padding(.horizontal, Theme.Space.l)
                .padding(.bottom, Theme.Space.l)
            }
        }
        .task(id: "\(period)-\(tasks.index.map { ObjectIdentifier($0).hashValue } ?? 0)") { await load() }
        .onChange(of: period) { _, _ in copied = false }
    }

    @ViewBuilder
    private func block(_ title: String, _ sections: [TaskSection], done: Bool) -> some View {
        if !sections.isEmpty {
            VStack(alignment: .leading, spacing: Theme.Space.s) {
                Text("\(title) · \(sections.reduce(0) { $0 + $1.rows.count })").font(.headline).foregroundStyle(.secondary)
                ForEach(sections, id: \.key) { s in
                    HStack(spacing: Theme.Space.s) {
                        if let c = Color(hex: s.color) { Circle().fill(c).frame(width: Theme.Calendar.dot, height: Theme.Calendar.dot) }
                        Text(s.title).font(.callout.weight(.semibold))
                    }
                    ForEach(s.rows, id: \.task.id) { r in
                        HStack(spacing: Theme.Space.s) {
                            Image(systemName: done ? "checkmark.square.fill" : "square").foregroundStyle(done ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                            Text(r.task.title).strikethrough(done).foregroundStyle(done ? .secondary : .primary)
                        }
                        .padding(.leading, Theme.Space.m)
                        .contentShape(Rectangle())
                        .onTapGesture { tasks.selection = r.task.id }
                    }
                }
            }
        }
    }

    /// Builds the summary off the main thread: it walks every task, which made opening Summary stutter.
    private func load() async {
        guard let index = tasks.index else { return }
        let core = tasks.core
        let cal = CalendarModel.mondayFirst()
        let today = cal.startOfDay(for: .now)
        let (from, to): (Date, Date) = switch period {
        case "today": (today, today)
        case "lastweek":
            { let start = cal.date(byAdding: .day, value: -7, to: cal.dateInterval(of: .weekOfYear, for: today)?.start ?? today) ?? today
              return (start, cal.date(byAdding: .day, value: 6, to: start) ?? start) }()
        case "month":
            { let i = cal.dateInterval(of: .month, for: today); return (i?.start ?? today, cal.date(byAdding: .day, value: -1, to: i?.end ?? today) ?? today) }()
        default:
            { let start = cal.dateInterval(of: .weekOfYear, for: today)?.start ?? today; return (start, cal.date(byAdding: .day, value: 6, to: start) ?? start) }()
        }
        let fromMs = Int64(from.timeIntervalSince1970 * 1000), toMs = Int64(to.timeIntervalSince1970 * 1000)
        let built = await _Concurrency.Task.detached(priority: .userInitiated) {
            core.summary(index: index, fromDay: fromMs, toDay: toMs)
        }.value
        if !_Concurrency.Task.isCancelled { summary = built }
    }
}

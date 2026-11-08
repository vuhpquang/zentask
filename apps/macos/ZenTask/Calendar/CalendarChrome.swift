import SwiftUI
@preconcurrency import ZenTaskShared

// Calendar chrome: the left bar (mini month + what to show), the View options sheet, and "+" new task.

extension CalendarViewOptions {
    /// A copy with some fields changed (Kotlin data classes have no default arguments in Swift).
    func with(lists: [String]? = nil, tags: [String]? = nil, showCompleted: Bool? = nil, showHabits: Bool? = nil,
              showCountdowns: Bool? = nil, colorBy: String? = nil, showLunar: Bool? = nil) -> CalendarViewOptions {
        CalendarViewOptions(lists: lists ?? self.lists, tags: tags ?? self.tags, showCompleted: showCompleted ?? self.showCompleted,
                            showHabits: showHabits ?? self.showHabits, showCountdowns: showCountdowns ?? self.showCountdowns,
                            colorBy: colorBy ?? self.colorBy, showLunar: showLunar ?? self.showLunar)
    }
}

/// Left bar: a mini month (lunar dates, dots on busy days, the shown range tinted) and what to show.
struct CalendarSideBar: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(TasksModel.self) private var tasks
    @State private var month: Date = .now
    @State private var listsOpen = true
    @State private var tagsOpen = false
    @State private var calendarsOpen = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                miniMonth
                Divider()
                show
            }
            .padding(Theme.Space.l)
        }
        .onAppear { month = cal.anchor }
        .onChange(of: cal.anchor) { _, a in month = a }
    }

    // MARK: Mini month

    private var miniMonth: some View {
        let c = cal.calendar
        let first = c.dateInterval(of: .month, for: month)?.start ?? month
        let gridStart = c.dateInterval(of: .weekOfYear, for: first)?.start ?? first
        let busy = Set(cal.days.filter { !$0.allDay.isEmpty || !$0.timed.isEmpty }.map { c.startOfDay(for: cal.date(of: $0)) })
        let range = cal.range
        let rangeEnd = c.date(byAdding: .day, value: range.count, to: range.first) ?? range.first
        let symbols = c.veryShortStandaloneWeekdaySymbols
        let weekdays = Array(symbols[(c.firstWeekday - 1)...] + symbols[..<(c.firstWeekday - 1)])
        return VStack(spacing: Theme.Space.xs) {
            HStack {
                Text(month.formatted(.dateTime.month(.wide).year())).font(.headline)
                Spacer()
                Button { month = c.date(byAdding: .month, value: -1, to: month) ?? month } label: { Image(systemName: "chevron.left") }
                Button { month = .now; cal.today() } label: { Image(systemName: "circle") }.help("Today")
                Button { month = c.date(byAdding: .month, value: 1, to: month) ?? month } label: { Image(systemName: "chevron.right") }
            }
            .buttonStyle(.borderless)
            HStack(spacing: 0) {
                ForEach(Array(weekdays.enumerated()), id: \.offset) { _, s in
                    Text(s).font(.caption2.weight(.medium)).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 7), spacing: Theme.Space.xxs) {
                ForEach(0..<42, id: \.self) { i in
                    let date = c.date(byAdding: .day, value: i, to: gridStart) ?? gridStart
                    let inMonth = c.isDate(date, equalTo: first, toGranularity: .month)
                    let isToday = c.isDateInToday(date)
                    let inRange = date >= range.first && date < rangeEnd
                    Button { cal.anchor = date } label: {
                        VStack(spacing: 0) {
                            Text("\(c.component(.day, from: date))")
                                .font(.callout.weight(isToday ? .bold : .regular).monospacedDigit())
                                .foregroundStyle(isToday ? AnyShapeStyle(.white) : inMonth ? AnyShapeStyle(.primary) : AnyShapeStyle(.tertiary))
                                .frame(width: Theme.Calendar.miniDay, height: Theme.Calendar.miniDay)
                                .background(Circle().fill(isToday ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear)))
                            if let lunar = cal.lunar(date) {
                                Text(lunar).font(.system(size: Theme.Calendar.lunarSize)).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Circle().fill(busy.contains(c.startOfDay(for: date)) ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear))
                                .frame(width: Theme.Calendar.miniDot, height: Theme.Calendar.miniDot)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Theme.Space.xxs)
                        .background(RoundedRectangle(cornerRadius: Theme.Radius.tile - 4, style: .continuous).fill(inRange ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: Show

    private var show: some View {
        let o = cal.viewOptions
        let all = o.lists.isEmpty && o.tags.isEmpty
        return VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text("Show").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            check("All tasks", on: all) { if !all { cal.setViewOptions(o.with(lists: [], tags: [])) } }
            DisclosureGroup("Lists", isExpanded: $listsOpen) {
                VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                    check("Inbox", on: o.lists.contains("inbox")) { toggleList("inbox") }
                    ForEach(tasks.listProjects, id: \.id) { p in
                        check(p.name, on: o.lists.contains(p.id), dot: Color(hex: p.color)) { toggleList(p.id) }
                    }
                }
                .padding(.top, Theme.Space.xs)
            }
            if !tasks.tags.isEmpty {
                DisclosureGroup("Tags", isExpanded: $tagsOpen) {
                    VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                        ForEach(tasks.tags, id: \.id) { t in
                            check("#" + (t.label.isEmpty ? t.name : t.label), on: o.tags.contains(t.name), dot: Color(hex: t.color)) { toggleTag(t.name) }
                        }
                    }
                    .padding(.top, Theme.Space.xs)
                }
            }
            let infos = cal.core.calendars().filter { !$0.source.deleted }
            if !infos.isEmpty {
                DisclosureGroup("Calendars", isExpanded: $calendarsOpen) {
                    VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                        ForEach(infos, id: \.source.id) { info in
                            check(info.source.name, on: !info.hidden, dot: Color(hex: info.source.color)) {
                                cal.core.setCalendarHidden(sourceId: info.source.id, hidden: !info.hidden)
                                _Concurrency.Task { await cal.reload() }
                            }
                        }
                    }
                    .padding(.top, Theme.Space.xs)
                }
            }
        }
    }

    private func toggleList(_ id: String) {
        let o = cal.viewOptions
        cal.setViewOptions(o.with(lists: o.lists.contains(id) ? o.lists.filter { $0 != id } : o.lists + [id]))
    }

    private func toggleTag(_ name: String) {
        let o = cal.viewOptions
        cal.setViewOptions(o.with(tags: o.tags.contains(name) ? o.tags.filter { $0 != name } : o.tags + [name]))
    }

    private func check(_ title: String, on: Bool, dot: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Theme.Space.s) {
                if let dot { Circle().fill(dot).frame(width: Theme.Calendar.dot, height: Theme.Calendar.dot) }
                Text(title).lineLimit(1)
                Spacer(minLength: Theme.Space.s)
                Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            }
            .frame(minHeight: Theme.List.pickerRow - Theme.Space.s)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// View options for the Calendar: colour, completed tasks, habits, countdowns, lunar dates.
struct CalendarViewOptionsSheet: View {
    @Environment(CalendarModel.self) private var cal
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let o = cal.viewOptions
        VStack(alignment: .leading, spacing: 0) {
            Form {
                Picker("Colour tasks by", selection: Binding(get: { o.colorBy }, set: { cal.setViewOptions(o.with(colorBy: $0)) })) {
                    Text("Priority").tag("priority")
                    Text("List").tag("list")
                }
                Section {
                    Toggle("Show completed tasks", isOn: Binding(get: { o.showCompleted }, set: { cal.setViewOptions(o.with(showCompleted: $0)) }))
                    Toggle("Show habits", isOn: Binding(get: { o.showHabits }, set: { cal.setViewOptions(o.with(showHabits: $0)) }))
                    Toggle("Show countdowns", isOn: Binding(get: { o.showCountdowns }, set: { cal.setViewOptions(o.with(showCountdowns: $0)) }))
                }
                Section {
                    Toggle("Show lunar dates", isOn: Binding(get: { o.showLunar }, set: { cal.setViewOptions(o.with(showLunar: $0)) }))
                }
            }
            .formStyle(.grouped)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction).controlSize(.large)
            }
            .padding(Theme.Space.l)
        }
        .frame(width: Theme.Size.sheetWidth + Theme.Space.xxl)
    }
}

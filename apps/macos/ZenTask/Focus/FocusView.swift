import SwiftUI
@preconcurrency import ZenTaskShared

/// The Focus area (rail ⌘6): the timer on the left; today's and total focus and the session timeline on the right.
/// Space starts or pauses, Esc stops.
struct FocusView: View {
    @Environment(FocusModel.self) private var focus
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        HStack(spacing: 0) {
            timer
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            FocusHistory()
                .frame(width: Theme.Focus.historyWidth)
        }
        .onAppear { focus.start(); tasks.start(); focus.setVisible(true) }
        .onDisappear { focus.setVisible(false) }
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.space) { toggle(); return .handled }
        .onKeyPress(.escape) { focus.stop(); return .handled }
    }

    private var timer: some View {
        @Bindable var focus = focus
        return VStack(spacing: Theme.Space.xl) {
            HStack {
                Text("Focus").font(.title2.weight(.bold))
                Spacer()
                Picker("Mode", selection: $focus.mode) {
                    Text("Pomo").tag(FocusModel.Mode.pomo)
                    Text("Stopwatch").tag(FocusModel.Mode.stopwatch)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .disabled(focus.phase != .idle)
                Spacer()
                Color.clear.frame(width: Theme.Focus.headerBalance, height: 1)
            }
            .padding(.horizontal, Theme.Space.xl)
            .padding(.top, Theme.Space.l)
            Spacer()
            taskPicker
            TimelineView(.periodic(from: .now, by: 1)) { context in
                ZStack {
                    Circle().stroke(.quaternary, lineWidth: Theme.Focus.ring)
                    Circle()
                        .trim(from: 0, to: focus.mode == .pomo ? focus.progress(at: context.date) : 1)
                        .stroke(focus.phase == .rest ? AnyShapeStyle(Color.green.gradient) : AnyShapeStyle(Theme.Habits.gradient),
                                style: StrokeStyle(lineWidth: Theme.Focus.ring, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .opacity(focus.phase == .idle && focus.mode == .stopwatch ? 0 : 1)
                    VStack(spacing: Theme.Space.s) {
                        Text(Self.clock(focus.phase == .idle && focus.mode == .pomo ? Double(focus.plan.focusMinutes) * 60 : focus.display(at: context.date)))
                            .font(.system(size: Theme.Focus.clock, weight: .light, design: .rounded).monospacedDigit())
                        Text(phaseTitle).font(.callout).foregroundStyle(.secondary)
                    }
                }
                .frame(width: Theme.Focus.dial, height: Theme.Focus.dial)
            }
            controls
            Spacer()
        }
    }

    private var taskPicker: some View {
        let open = (tasks.index?.allTasks ?? []).filter { $0.isOpen && $0.trashedAt == nil && $0.parentId == nil }
        let today = Calendar.current.startOfDay(for: .now)
        let soon = open.filter { tasks.day(of: $0).map { $0 <= today } ?? false }
        let current = focus.taskId.flatMap { id in tasks.index?.task(id: id) }
        return Menu {
            Button("No task") { focus.taskId = nil }
            if !soon.isEmpty {
                Section("Today") { ForEach(soon.prefix(30), id: \.id) { t in Button(t.title) { focus.taskId = t.id } } }
            }
            Section("Other tasks") { ForEach(open.filter { t in !soon.contains { $0.id == t.id } }.prefix(40), id: \.id) { t in Button(t.title) { focus.taskId = t.id } } }
        } label: {
            HStack(spacing: Theme.Space.xs) {
                Text(current?.title ?? "Focus").lineLimit(1)
                Image(systemName: "chevron.right").font(.caption)
            }
            .foregroundStyle(.secondary)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Pick the task you're focusing on")
    }

    @ViewBuilder
    private var controls: some View {
        HStack(spacing: Theme.Space.m) {
            switch focus.phase {
            case .idle:
                Button { focus.begin() } label: { Text("Start").frame(width: Theme.Focus.button) }
                    .buttonStyle(.borderedProminent)
            case .focus:
                Button { focus.pause() } label: { Text("Pause").frame(width: Theme.Focus.button) }
                    .buttonStyle(.borderedProminent)
                Button(focus.mode == .stopwatch ? "Stop and save" : "Stop") { focus.stop() }
            case .paused:
                Button { focus.resume() } label: { Text("Continue").frame(width: Theme.Focus.button) }
                    .buttonStyle(.borderedProminent)
                Button(focus.mode == .stopwatch ? "Stop and save" : "Stop") { focus.stop() }
            case .rest:
                Button { focus.skipBreak() } label: { Text("Skip break").frame(width: Theme.Focus.button) }
                    .buttonStyle(.bordered)
            }
        }
        .controlSize(.extraLarge)
    }

    private var phaseTitle: String {
        switch focus.phase {
        case .idle: focus.mode == .pomo ? "\(focus.plan.focusMinutes)-minute pomo" : "Stopwatch"
        case .focus: "Focusing"
        case .paused: "Paused"
        case .rest: "Break"
        }
    }

    private func toggle() {
        switch focus.phase {
        case .idle: focus.begin()
        case .focus: focus.pause()
        case .paused: focus.resume()
        case .rest: focus.skipBreak()
        }
    }

    static func clock(_ seconds: Double) -> String {
        let s = Int(seconds.rounded(.down))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }
}

/// Overview cards and the record timeline, newest first.
private struct FocusHistory: View {
    @Environment(FocusModel.self) private var focus
    @Environment(TasksModel.self) private var tasks

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                Text("Overview").font(.title3.weight(.bold))
                let s = focus.stats
                LazyVGrid(columns: [GridItem(.flexible(), spacing: Theme.Space.m), GridItem(.flexible())], spacing: Theme.Space.m) {
                    card("Today's pomos", "\(s?.todayPomos ?? 0)")
                    card("Today's focus", Self.minutes(Int(s?.todayMinutes ?? 0)))
                    card("Total pomos", "\(s?.totalPomos ?? 0)")
                    card("Total focus", Self.minutes(Int(s?.totalMinutes ?? 0)))
                }
                Text("Focus record").font(.title3.weight(.bold)).padding(.top, Theme.Space.s)
                if (s?.days ?? []).isEmpty {
                    Text("Finished pomos and stopwatch sessions show up here.").font(.callout).foregroundStyle(.secondary)
                }
                ForEach(s?.days ?? [], id: \.date) { day in
                    let date = Date(timeIntervalSince1970: Double(focus.core.millisOf(stamp: Int32(day.date.year) * 10_000 + Int32(day.date.month.ordinal + 1) * 100 + Int32(day.date.day))) / 1000)
                    HStack {
                        Text(date.formatted(.dateTime.day().month(.abbreviated).year())).font(.headline)
                        Spacer()
                        Text(Self.minutes(Int(day.minutes))).font(.callout).foregroundStyle(.secondary)
                    }
                    ForEach(day.records, id: \.id) { r in
                        let start = Date(timeIntervalSince1970: Double(r.start) / 1000)
                        let end = Date(timeIntervalSince1970: Double(r.end) / 1000)
                        HStack(alignment: .top, spacing: Theme.Space.m) {
                            Image(systemName: r.kind == FocusRecord.companion.KIND_POMO ? "timer" : "stopwatch")
                                .foregroundStyle(.tint)
                            VStack(alignment: .leading, spacing: Theme.Space.xxs) {
                                Text("\(start.formatted(date: .omitted, time: .shortened)) – \(end.formatted(date: .omitted, time: .shortened))")
                                    .font(.callout.monospacedDigit())
                                if let id = r.taskId, let t = tasks.index?.task(id: id) {
                                    Text(t.title).font(.callout.weight(.medium)).lineLimit(2)
                                }
                            }
                            Spacer()
                            Text("\(r.minutes)m").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                        }
                        .contextMenu { Button("Delete record", systemImage: "trash", role: .destructive) { focus.core.focusActions.delete(id: r.id) } }
                    }
                }
            }
            .padding(Theme.Space.xl)
        }
    }

    private func card(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title2.weight(.semibold).monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Detail.cardPadding)
        .background(RoundedRectangle(cornerRadius: Theme.Detail.cardRadius, style: .continuous).fill(.background.secondary))
    }

    static func minutes(_ m: Int) -> String { m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m" }
}

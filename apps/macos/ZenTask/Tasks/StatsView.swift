import Charts
import SwiftUI
@preconcurrency import ZenTaskShared

/// Sidebar → Statistics: tasks completed per day (30 days), this week vs last, streaks, lists, focus.
struct StatsView: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.themeColors) private var themeColors
    @State private var stats: ProductivityStats?

    /// [preloaded]: stats to show right away (snapshots); otherwise they load off the main thread.
    init(preloaded: ProductivityStats? = nil) { _stats = State(initialValue: preloaded) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                Text("Statistics").font(.title2.weight(.bold))
                if let s = stats {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Theme.Space.m), count: 2), spacing: Theme.Space.m) {
                        tile("This week", "\(s.thisWeek)", trend(s))
                        tile("Streak", "\(s.streak) day\(s.streak == 1 ? "" : "s")", "Best \(s.bestStreak)")
                        tile("Last 30 days", "\(s.total)", s.best.map { "Best day \(dayLabel($0.stamp)): \($0.done)" } ?? "")
                        tile("Focus this week", minutes(Int(s.focusMinutesThisWeek)), "")
                    }
                    card("Completed per day") {
                        Chart(s.days, id: \.stamp) { d in
                            BarMark(x: .value("Day", date(d.stamp), unit: .day), y: .value("Done", d.done))
                                .foregroundStyle(themeColors.accent.gradient)
                                .cornerRadius(Theme.Radius.chartBar)
                        }
                        .chartXAxis { AxisMarks(values: .stride(by: .day, count: 7)) { _ in AxisGridLine(); AxisValueLabel(format: .dateTime.day().month()) } }
                        .frame(height: Theme.Stats.chartHeight)
                    }
                    if !s.byList.isEmpty {
                        card("By list (30 days)") {
                            VStack(spacing: Theme.Space.s) {
                                let top = max(1, Int(s.byList.first?.done ?? 1))
                                ForEach(s.byList, id: \.name) { l in
                                    HStack(spacing: Theme.Space.s) {
                                        Text(l.name).lineLimit(1).frame(width: Theme.Stats.listName, alignment: .leading)
                                        GeometryReader { g in
                                            RoundedRectangle(cornerRadius: Theme.Radius.chartBar, style: .continuous)
                                                .fill((Color(hex: l.color) ?? themeColors.accent).gradient)
                                                .frame(width: max(Theme.Space.xs, g.size.width * CGFloat(l.done) / CGFloat(top)))
                                        }
                                        .frame(height: Theme.Stats.listBar)
                                        Text("\(l.done)").monospacedDigit().foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(Theme.Space.xl)
        }
        .task(id: tasks.index.map(ObjectIdentifier.init)) {
            guard let index = tasks.index else { return }
            let core = tasks.core
            stats = await _Concurrency.Task.detached(priority: .userInitiated) { core.productivity(index: index) }.value
        }
    }

    private func trend(_ s: ProductivityStats) -> String {
        let diff = Int(s.thisWeek) - Int(s.lastWeek)
        if s.lastWeek == 0 { return "Last week \(s.lastWeek)" }
        return diff >= 0 ? "▲ \(diff) vs last week" : "▼ \(-diff) vs last week"
    }

    private func minutes(_ m: Int) -> String { m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m" }

    private func date(_ stamp: Int32) -> Date {
        let s = Int(stamp)
        return Calendar.current.date(from: DateComponents(year: s / 10_000, month: s / 100 % 100, day: s % 100)) ?? .now
    }

    private func dayLabel(_ stamp: Int32) -> String { date(stamp).formatted(.dateTime.day().month()) }

    private func tile(_ title: String, _ value: String, _ footnote: String) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.xxs) {
            Text(title).font(.callout).foregroundStyle(.secondary)
            Text(value).font(.title.weight(.bold).monospacedDigit())
            Text(footnote.isEmpty ? " " : footnote).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Space.m)
        .background(.background, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
    }

    private func card<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text(title).font(.headline)
            content()
        }
        .padding(Theme.Space.m)
        .background(.background, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
    }
}

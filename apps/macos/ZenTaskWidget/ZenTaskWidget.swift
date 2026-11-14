import SwiftUI
import WidgetKit

/// Desktop / Notification Center widgets: Today's tasks and the next countdowns. Read-only: clicking a task opens
/// it in ZenTask (zentask://task/<id>). Data comes from the snapshot the app writes after every change.
@main
struct ZenTaskWidgets: WidgetBundle {
    var body: some Widget {
        TodayWidget()
        CountdownWidget()
    }
}

private let isDev = Bundle.main.bundleIdentifier?.contains(".dev.") == true

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: WidgetSnapshot(
            today: [.init(id: "1", title: "Plan the week", detail: "09:00", overdue: false, priority: 5),
                    .init(id: "2", title: "Call Mom", detail: "", overdue: false, priority: 0)],
            countdowns: [.init(name: "Trip", daysLeft: 12, date: "Thu, 26 Nov")]))
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : SnapshotEntry(date: .now, snapshot: WidgetSnapshot.load(dev: isDev) ?? WidgetSnapshot()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let entry = SnapshotEntry(date: .now, snapshot: WidgetSnapshot.load(dev: isDev) ?? WidgetSnapshot())
        // The app reloads the timelines after each change; refresh at least every 30 minutes (and past midnight).
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(30 * 60))))
    }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "zentask.today", provider: SnapshotProvider()) { entry in
            TodayWidgetView(entry: entry).containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Today")
        .description("Today's tasks. Click one to open it in ZenTask.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    private var limit: Int { switch family { case .systemSmall: 3; case .systemMedium: 4; default: 10 } }

    var body: some View {
        let tasks = entry.snapshot.today
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("Today").font(.headline).foregroundStyle(.tint)
                Spacer()
                Text("\(tasks.count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            if tasks.isEmpty {
                Spacer()
                Label("All done", systemImage: "checkmark.circle").foregroundStyle(.secondary)
                Spacer()
            } else {
                ForEach(tasks.prefix(limit), id: \.id) { t in
                    Link(destination: URL(string: "zentask://task/\(t.id)")!) {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Image(systemName: "square").font(.caption).foregroundStyle(priorityColor(t.priority))
                            Text(t.title).font(.callout).lineLimit(1)
                            Spacer(minLength: 4)
                            if !t.detail.isEmpty && family != .systemSmall {
                                Text(t.detail).font(.caption).foregroundStyle(t.overdue ? .red : .secondary).lineLimit(1)
                            }
                        }
                    }
                }
                if tasks.count > limit {
                    Text("+\(tasks.count - limit) more").font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        }
        .widgetURL(URL(string: "zentask://today"))
    }

    private func priorityColor(_ p: Int) -> Color {
        switch p { case 5: .red; case 3: .orange; case 1: .blue; default: .secondary }
    }
}

struct CountdownWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "zentask.countdown", provider: SnapshotProvider()) { entry in
            CountdownWidgetView(entry: entry).containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Countdown")
        .description("Days left to your next countdowns.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct CountdownWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    var body: some View {
        let items = entry.snapshot.countdowns
        if let next = items.first {
            VStack(alignment: .leading, spacing: 4) {
                Text(next.name).font(.headline).lineLimit(1)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(next.daysLeft == 0 ? "Today" : "\(next.daysLeft)").font(.system(size: 40, weight: .bold, design: .rounded)).foregroundStyle(.tint)
                    if next.daysLeft > 0 { Text(next.daysLeft == 1 ? "day" : "days").foregroundStyle(.secondary) }
                }
                Text(next.date).font(.caption).foregroundStyle(.secondary)
                if family == .systemMedium {
                    ForEach(items.dropFirst().prefix(3), id: \.name) { c in
                        HStack {
                            Text(c.name).font(.caption).lineLimit(1)
                            Spacer()
                            Text(c.daysLeft == 0 ? "today" : "\(c.daysLeft)d").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        } else {
            Label("No countdowns", systemImage: "hourglass").foregroundStyle(.secondary)
        }
    }
}

import SwiftUI

/// A month grid that fills its width (the graphical `DatePicker` has a fixed, small size).
/// Picking a day keeps the selection's time of day.
struct MonthPicker: View {
    @Binding var selection: Date
    @State private var month = Date.now
    private var cal: Calendar { Calendar.current }

    var body: some View {
        let first = cal.dateInterval(of: .month, for: month)?.start ?? month
        let gridStart = cal.dateInterval(of: .weekOfYear, for: first)?.start ?? first
        let symbols = cal.shortStandaloneWeekdaySymbols
        let weekdays = Array(symbols[(cal.firstWeekday - 1)...] + symbols[..<(cal.firstWeekday - 1)])
        VStack(spacing: Theme.Space.xs) {
            HStack {
                Text(month.formatted(.dateTime.month(.wide).year())).font(.headline)
                Spacer()
                Button { shift(-1) } label: { Image(systemName: "chevron.left") }.help("Previous month")
                Button { month = selection } label: { Image(systemName: "circle.fill").font(.caption2) }.help("Selected month")
                Button { shift(1) } label: { Image(systemName: "chevron.right") }.help("Next month")
            }
            .buttonStyle(.borderless)
            .padding(.bottom, Theme.Space.xs)
            HStack(spacing: 0) {
                ForEach(Array(weekdays.enumerated()), id: \.offset) { _, s in
                    Text(s).font(.caption.weight(.medium)).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 7), spacing: Theme.Space.xxs) {
                ForEach(0..<42, id: \.self) { i in
                    let day = cal.date(byAdding: .day, value: i, to: gridStart) ?? gridStart
                    cell(day, inMonth: cal.isDate(day, equalTo: first, toGranularity: .month))
                }
            }
        }
        .onAppear { month = selection }
        // Quick picks (Today, Next Monday…) move the grid to the new day's month.
        .onChange(of: selection) { _, new in
            if !cal.isDate(new, equalTo: month, toGranularity: .month) { month = new }
        }
    }

    private func cell(_ day: Date, inMonth: Bool) -> some View {
        let selected = cal.isDate(day, inSameDayAs: selection)
        let today = cal.isDateInToday(day)
        let text: AnyShapeStyle = selected ? AnyShapeStyle(.white) : today ? AnyShapeStyle(.tint) : inMonth ? AnyShapeStyle(.primary) : AnyShapeStyle(.tertiary)
        return Button { pick(day) } label: {
            Text("\(cal.component(.day, from: day))")
                .font(.body.weight(selected || today ? .semibold : .regular).monospacedDigit())
                .foregroundStyle(text)
                .frame(width: Theme.Detail.pickerDay, height: Theme.Detail.pickerDay)
                .background(Circle().fill(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.clear)))
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(day.formatted(date: .complete, time: .omitted))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func shift(_ months: Int) {
        month = cal.date(byAdding: .month, value: months, to: month) ?? month
    }

    private func pick(_ day: Date) {
        let t = cal.dateComponents([.hour, .minute], from: selection)
        selection = cal.date(bySettingHour: t.hour ?? 0, minute: t.minute ?? 0, second: 0, of: day) ?? day
    }
}

import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class CalendarModelTests: XCTestCase {
    private func model(at date: String) -> CalendarModel {
        let m = CalendarModel(core: ZenTaskCore.companion.open(dbPath: nil))
        m.anchor = ISO8601DateFormatter().date(from: date)!
        return m
    }

    func testWeekStartsMondayAndMonthGridIsSixWeeks() {
        let m = model(at: "2026-11-08T05:00:00Z") // Sunday 8 Nov (UTC+7: noon)
        m.mode = .week
        let week = m.range
        XCTAssertEqual(week.count, 7)
        XCTAssertEqual(m.calendar.component(.weekday, from: week.first), 2, "Monday")
        XCTAssertEqual(m.calendar.component(.day, from: week.first), 2, "the week of Sunday 8 Nov starts Monday 2 Nov")
        m.mode = .month
        XCTAssertEqual(m.range.count, 42)
        XCTAssertEqual(m.calendar.component(.weekday, from: m.range.first), 2)
        m.mode = .day
        XCTAssertEqual(m.range.count, 1)
    }

    func testPrevNextAndToday() {
        let m = model(at: "2026-11-08T05:00:00Z")
        m.mode = .week
        m.next()
        XCTAssertEqual(m.calendar.component(.day, from: m.range.first), 9)
        m.previous(); m.previous()
        XCTAssertEqual(m.calendar.component(.day, from: m.range.first), 26)
        m.today()
        XCTAssertTrue(m.calendar.isDateInToday(m.anchor))
    }

    func testDroppingATaskSchedulesItSnappedTo15Minutes() async throws {
        let m = model(at: "2026-11-08T05:00:00Z")
        m.mode = .day
        let task = m.core.actions.create(title: "Call", filter: nil, projectId: nil, priority: nil, tags: [], due: nil, allDay: true)
        await m.reload()
        let day = try XCTUnwrap(m.days.first)
        m.schedule(taskId: task.id, on: day, minute: 9 * 60 + 7)
        let t = try XCTUnwrap(m.core.task(id: task.id))
        XCTAssertFalse(t.isAllDay)
        let due = Date(timeIntervalSince1970: Double(try XCTUnwrap(t.dueDate).int64Value) / 1000)
        XCTAssertEqual(m.calendar.dateComponents([.hour, .minute], from: due), DateComponents(hour: 9, minute: 0))
        m.schedule(taskId: task.id, on: day, minute: nil)
        XCTAssertEqual(m.core.task(id: task.id)?.isAllDay, true)
        await m.reload()
        XCTAssertEqual(m.days.first?.allDay.map(\.title), ["Call"])
    }
}

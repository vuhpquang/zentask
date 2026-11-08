import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class HabitsModelTests: XCTestCase {
    private func model() -> HabitsModel { HabitsModel(core: ZenTaskCore.companion.open(dbPath: nil)) }

    func testDraftRuleRoundTrips() {
        var d = HabitDraft()
        d.name = "Gym"
        d.frequency = .weekdays([2, 4, 6])
        XCTAssertEqual(d.repeatRule, "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")
        d.frequency = .timesAWeek(3)
        XCTAssertEqual(d.repeatRule, "RRULE:FREQ=WEEKLY;TT_TIMES=3")
        d.frequency = .weekdays([])
        XCTAssertFalse(d.isValid, "no day picked")

        let m = model()
        let h = m.core.habitActions.create(name: "Gym", icon: "🏋️", color: nil, type: "BOOLEAN", goal: 1, step: 1, unit: "",
                                           repeatRule: "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")
        let back = HabitDraft(habit: h)
        XCTAssertEqual(back.frequency, .weekdays([2, 4, 6]))
        XCTAssertEqual(back.icon, "🏋️")
        let imported = m.core.habitActions.create(name: "Read", icon: "habit_reading", color: nil, type: "BOOLEAN", goal: 1, step: 1, unit: "", repeatRule: "RRULE:FREQ=DAILY;INTERVAL=1")
        XCTAssertEqual(HabitDraft(habit: imported).icon, "", "TickTick icon names aren't shown as text")
        XCTAssertEqual(HabitLook.symbol(imported), "book.fill")
    }

    func testTapTogglesTodayAndIgnoresFuture() async throws {
        let m = model()
        var d = HabitDraft()
        d.name = "Read"
        m.save(d, original: nil)
        await m.reload()
        let row = try XCTUnwrap(m.selected)
        let today = m.date(m.today)
        m.tap(row, on: today)
        await m.reload()
        XCTAssertTrue(try XCTUnwrap(m.selected).doneToday)
        XCTAssertEqual(m.selected?.stats.currentStreak, 1)
        m.tap(try XCTUnwrap(m.selected), on: Calendar.current.date(byAdding: .day, value: 1, to: today)!)
        await m.reload()
        XCTAssertEqual(m.selected?.doneStamps.count, 1, "future days can't be checked in")
        m.tap(try XCTUnwrap(m.selected), on: today)
        await m.reload()
        XCTAssertEqual(m.selected?.doneToday, false, "second click undoes")
    }

    func testNumberHabitAccumulatesToGoal() async throws {
        let m = model()
        var d = HabitDraft()
        d.name = "Water"
        d.isNumber = true
        d.goal = 3
        d.unit = "cups"
        m.save(d, original: nil)
        await m.reload()
        let today = m.date(m.today)
        m.tap(try XCTUnwrap(m.selected), on: today)
        m.add(try XCTUnwrap(m.selected), amount: 1.5, on: today)
        await m.reload()
        XCTAssertEqual(m.selected?.todayValue, 2.5)
        XCTAssertEqual(m.selected?.doneToday, false)
        m.add(try XCTUnwrap(m.selected), amount: 0.5, on: today)
        await m.reload()
        XCTAssertEqual(m.selected?.doneToday, true)
        m.clear(try XCTUnwrap(m.selected), on: today)
        await m.reload()
        XCTAssertEqual(m.selected?.todayValue, 0)
    }

    func testArchiveMovesHabitOutOfActive() async throws {
        let m = model()
        var d = HabitDraft()
        d.name = "Old"
        m.save(d, original: nil)
        await m.reload()
        m.archive(try XCTUnwrap(m.selected).habit)
        await m.reload()
        XCTAssertTrue(m.active.isEmpty)
        XCTAssertEqual(m.archived.count, 1)
    }

    func testMonthGridIsWholeWeeksStartingMonday() {
        let m = model()
        m.month = ISO8601DateFormatter().date(from: "2026-11-15T05:00:00Z")!
        let grid = m.monthGrid()
        XCTAssertTrue(grid.allSatisfy { $0.count == 7 })
        XCTAssertEqual(grid.flatMap { $0 }.compactMap { $0 }.count, 30)
        XCTAssertNil(grid[0][0], "1 Nov 2026 is a Sunday: six blanks first")
        XCTAssertEqual(m.calendar.component(.day, from: grid[0][6]!), 1)
    }

    func testCountdownDraftSavesAndEdits() async throws {
        let m = CountdownsModel(core: ZenTaskCore.companion.open(dbPath: nil))
        var d = CountdownDraft()
        d.name = "Mom"
        d.type = "BIRTHDAY"
        d.yearly = true
        d.date = m.calendar.date(from: DateComponents(year: 1960, month: 11, day: 10))!
        m.save(d, original: nil)
        await m.reload()
        let card = try XCTUnwrap(m.cards.first)
        XCTAssertEqual(card.countdown.date, 19601110)
        XCTAssertEqual(card.countdown.`repeat`, "yearly")
        XCTAssertGreaterThan(card.years, 60)
        var edit = CountdownDraft(card.countdown, calendar: m.calendar)
        XCTAssertEqual([edit.name, edit.type, "\(edit.yearly)", "\(edit.stamp(m.calendar))"], [d.name, d.type, "\(d.yearly)", "19601110"])
        edit.name = "Mẹ"
        m.save(edit, original: card.countdown)
        await m.reload()
        XCTAssertEqual(m.cards.map(\.countdown.name), ["Mẹ"])
        m.archive(try XCTUnwrap(m.cards.first).countdown)
        await m.reload()
        XCTAssertTrue(m.active.isEmpty)
    }

    func testEditingKeepsRulesAndIconsTheEditorCantShow() async throws {
        let m = model()
        let h = m.core.habitActions.create(name: "Run", icon: "habit_running", color: nil, type: "BOOLEAN", goal: 1, step: 1, unit: "",
                                           repeatRule: "RRULE:FREQ=DAILY;INTERVAL=2")
        var d = HabitDraft(habit: h)
        d.name = "Run 5k"
        m.save(d, original: h)
        await m.reload()
        let saved = try XCTUnwrap(m.rows.first?.habit)
        XCTAssertEqual(saved.name, "Run 5k")
        XCTAssertEqual(saved.repeatRule, "RRULE:FREQ=DAILY;INTERVAL=2", "every 2 days survives a rename")
        XCTAssertEqual(saved.icon, "habit_running", "TickTick icon name kept")
        var changed = HabitDraft(habit: saved)
        changed.frequency = .timesAWeek(3)
        changed.icon = "🏃"
        m.save(changed, original: saved)
        await m.reload()
        XCTAssertEqual(m.rows.first?.habit.repeatRule, "RRULE:FREQ=WEEKLY;TT_TIMES=3")
        XCTAssertEqual(m.rows.first?.habit.icon, "🏃")
    }

    func testMonthRateSkipsDaysBeforeCreationAndOffDays() async throws {
        let m = model()
        m.month = .now
        let h = m.core.habitActions.create(name: "Run", icon: nil, color: nil, type: "BOOLEAN", goal: 1, step: 1, unit: "",
                                           repeatRule: "RRULE:FREQ=DAILY;INTERVAL=2")
        await m.reload()
        let today = m.date(m.today)
        m.tap(try XCTUnwrap(m.rows.first), on: today)
        await m.reload()
        let stats = m.monthStats(try XCTUnwrap(m.rows.first { $0.habit.id == h.id }))
        XCTAssertEqual(stats.done, 1)
        XCTAssertEqual(stats.rate, 1, "created today: today is the only due day so far")
    }

    func testCalendarViewOptionsAndHabitCheckInFromTheCalendar() async throws {
        let cal = CalendarModel(core: ZenTaskCore.companion.open(dbPath: nil))
        cal.mode = .day
        cal.anchor = .now
        let h = cal.core.habitActions.create(name: "Gym", icon: nil, color: nil, type: "BOOLEAN", goal: 1, step: 1, unit: "",
                                             repeatRule: "RRULE:FREQ=DAILY;INTERVAL=1")
        await cal.reload()
        let entry = try XCTUnwrap(cal.days.first?.allDay.first { $0.kind == "habit" })
        XCTAssertTrue(entry.id.hasPrefix(h.id + "@"))
        cal.core.toggleHabitEntry(entryId: entry.id)
        await cal.reload()
        XCTAssertEqual(cal.days.first?.allDay.first { $0.kind == "habit" }?.done, true)
        cal.setViewOptions(cal.viewOptions.with(showHabits: false))
        await cal.reload()
        XCTAssertNil(cal.days.first?.allDay.first { $0.kind == "habit" })
        XCTAssertFalse(cal.core.calendarView().showHabits, "saved")
        XCTAssertNotNil(cal.lunar(.now))
        cal.setViewOptions(cal.viewOptions.with(showLunar: false))
        XCTAssertNil(cal.lunar(.now))
    }

    func testSearchFoldsVietnameseAccents() {
        XCTAssertEqual(SearchOverlay.fold("Đồng Hồ"), "dong ho")
        XCTAssertTrue(SearchOverlay.fold("Chụp hình đồ kỉ niệm").contains(SearchOverlay.fold("do ki niem")))
    }

    func testHabitsWithoutSectionsFormOneUntitledGroupAndWeekIsMondayFirst() async throws {
        let m = model()
        var d = HabitDraft()
        d.name = "Read"
        m.save(d, original: nil)
        await m.reload()
        XCTAssertEqual(m.groups.count, 1)
        XCTAssertNil(m.groups.first?.title)
        XCTAssertEqual(m.week.count, 7)
        XCTAssertEqual(m.calendar.component(.weekday, from: m.weekDays()[0]), 2, "Monday")
        XCTAssertEqual(m.weekDays().map(m.stamp), m.week.map(\.stamp))
        m.toggleFold("all")
        XCTAssertTrue(m.isFolded("all"))
        m.toggleFold("all")
    }

    func testKeyComboStoresAndDisplays() throws {
        let combo = KeyCombo(keyCode: 45, modifiers: [.control, .option, .capsLock])
        XCTAssertEqual(KeyCombo(stored: combo.stored), combo, "round trip, caps lock ignored")
        XCTAssertTrue(combo.display.hasPrefix("⌃⌥"))
        XCTAssertNil(KeyCombo(stored: "junk"))
        XCTAssertNotEqual(combo.carbonModifiers, 0)
    }
}

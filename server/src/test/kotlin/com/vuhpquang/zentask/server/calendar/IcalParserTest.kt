package com.vuhpquang.zentask.server.calendar

import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcalParserTest {
    private val from = 1_790_000_000_000L // 2026-09-21
    private val until = 1_800_000_000_000L // 2027-01-15
    private fun ms(s: String) = java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()

    private val feed = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:one@test
DTSTART;TZID=Asia/Ho_Chi_Minh:20261109T090000
DTEND;TZID=Asia/Ho_Chi_Minh:20261109T100000
SUMMARY:Sprint planning\, team A
DESCRIPTION:Line one\nline two that is folded
  across lines
LOCATION:Room 1
BEGIN:VALARM
SUMMARY:alarm inside
END:VALARM
END:VEVENT
BEGIN:VEVENT
UID:allday@test
DTSTART;VALUE=DATE:20261110
DTEND;VALUE=DATE:20261112
SUMMARY:Offsite
END:VEVENT
BEGIN:VEVENT
UID:weekly@test
DTSTART:20261102T020000Z
DTEND:20261102T023000Z
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE:20261109T020000Z
SUMMARY:Weekly sync
END:VEVENT
BEGIN:VEVENT
UID:weekly@test
RECURRENCE-ID:20261116T020000Z
DTSTART:20261116T030000Z
DTEND:20261116T033000Z
SUMMARY:Weekly sync (moved)
END:VEVENT
BEGIN:VEVENT
UID:gone@test
DTSTART:20261111T020000Z
STATUS:CANCELLED
SUMMARY:Cancelled
END:VEVENT
END:VCALENDAR"""

    @Test
    fun parsesTimesTextAndAllDay() {
        val e = IcalParser.parse(feed, from, until)
        val one = e.single { it.uid == "one@test" }
        assertEquals("Sprint planning, team A", one.summary)
        assertEquals("Line one\nline two that is folded across lines", one.description)
        assertEquals(ms("2026-11-09T09:00:00+07:00"), one.start)
        assertEquals(ms("2026-11-09T10:00:00+07:00"), one.end)
        val offsite = e.single { it.uid == "allday@test" }
        assertTrue(offsite.allDay)
        assertEquals(ms("2026-11-10T00:00:00+07:00"), offsite.start)
        assertEquals(ms("2026-11-12T00:00:00+07:00"), offsite.end)
        assertTrue(e.none { it.summary == "Cancelled" || it.summary == "alarm inside" })
    }

    @Test
    fun expandsRecurrenceWithExdateAndOverride() {
        val weekly = IcalParser.parse(feed, from, until).filter { it.uid == "weekly@test" }.sortedBy { it.start }
        assertEquals(
            listOf(ms("2026-11-02T02:00:00Z"), ms("2026-11-16T03:00:00Z"), ms("2026-11-23T02:00:00Z")),
            weekly.map { it.start },
            "Nov 9 excluded, Nov 16 replaced by its override, COUNT=4 ends after Nov 23",
        )
        assertEquals("Weekly sync (moved)", weekly[1].summary)
    }

    @Test
    fun windowLimitsOccurrences() {
        val narrow = IcalParser.parse(feed, ms("2026-11-20T00:00:00Z"), ms("2026-11-30T00:00:00Z"))
        assertEquals(listOf("Weekly sync"), narrow.map { it.summary })
    }

    @Test
    fun longRunningSeriesStillReachesTheWindow() {
        val old = """BEGIN:VCALENDAR
BEGIN:VEVENT
UID:standup@test
DTSTART:20150105T020000Z
DTEND:20150105T021500Z
RRULE:FREQ=DAILY
SUMMARY:Daily standup
END:VEVENT
END:VCALENDAR"""
        val e = IcalParser.parse(old, ms("2026-11-09T00:00:00Z"), ms("2026-11-11T00:00:00Z"))
        assertEquals(2, e.size, "a series started years ago still shows its current occurrences")
    }

    @Test
    fun allDayEventsKeepTheirZone() {
        val e = IcalParser.parse(feed, from, until).single { it.uid == "allday@test" }
        assertEquals("Asia/Ho_Chi_Minh", e.timeZone)
    }

    @Test
    fun fetchErrorsNeverCarryTheSecretUrl() = kotlinx.coroutines.test.runTest {
        val secret = "https://calendar.google.com/calendar/ical/x/private-SECRET123/basic.ics"
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { throw java.io.IOException("Request timeout has expired [url=$secret]") })
        val e = kotlin.test.assertFailsWith<IllegalStateException> { IcalHttpFetcher(http).events(secret, 0, 1) }
        assertTrue(!e.message.orEmpty().contains("SECRET123") && e.cause == null, e.message)
        val notFound = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond404() })
        val e2 = kotlin.test.assertFailsWith<IllegalStateException> { IcalHttpFetcher(notFound).events(secret, 0, 1) }
        assertTrue(!e2.message.orEmpty().contains("SECRET123"), e2.message)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respond404() =
        respond("nope", io.ktor.http.HttpStatusCode.NotFound)
}

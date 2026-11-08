package com.vuhpquang.zentask.tasks

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RecurrenceTest {
    private val hcm = TimeZone.of("Asia/Ho_Chi_Minh")
    private val la = TimeZone.of("America/Los_Angeles")

    private fun at(s: String, zone: TimeZone = hcm): Instant = LocalDateTime.parse(s).toInstant(zone)
    private fun local(i: Instant?, zone: TimeZone = hcm) = i?.toLocalDateTime(zone)?.toString()

    private fun next(rule: String, from: String, zone: TimeZone = hcm, done: Int = 0) =
        local(Recurrence.next(rule, at(from, zone), zone, done), zone)

    @Test
    fun daily() {
        assertEquals("2026-11-09T09:00", next("RRULE:FREQ=DAILY;INTERVAL=1", "2026-11-08T09:00"))
        assertEquals("2026-11-11T09:00", next("FREQ=DAILY;INTERVAL=3", "2026-11-08T09:00"))
    }

    @Test
    fun weeklyByDay() {
        // 2026-11-08 is a Sunday.
        assertEquals("2026-11-09T07:00", next("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-11-08T07:00"))
        assertEquals("2026-11-11T07:00", next("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-11-09T07:00"))
        assertEquals("2026-11-16T07:00", next("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-11-13T07:00"))
        assertEquals("2026-11-15T07:00", next("RRULE:FREQ=WEEKLY", "2026-11-08T07:00"))
        assertEquals("2026-11-23T07:00", next("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", "2026-11-09T07:00"))
    }

    @Test
    fun monthlyClampsToShortMonths() {
        assertEquals("2026-11-30T00:00", next("RRULE:FREQ=MONTHLY;BYMONTHDAY=31", "2026-10-31T00:00"))
        assertEquals("2026-12-31T00:00", next("RRULE:FREQ=MONTHLY;BYMONTHDAY=31", "2026-11-30T00:00"))
        assertEquals("2026-12-08T10:00", next("RRULE:FREQ=MONTHLY", "2026-11-08T10:00"))
    }

    @Test
    fun yearly() {
        assertEquals("2029-02-28T08:00", next("RRULE:FREQ=YEARLY", "2028-02-29T08:00"))
    }

    @Test
    fun countAndUntilEnd() {
        assertEquals("2026-11-09T09:00", next("RRULE:FREQ=DAILY;COUNT=3", "2026-11-08T09:00", done = 1))
        assertNull(next("RRULE:FREQ=DAILY;COUNT=3", "2026-11-08T09:00", done = 2), "third occurrence is the last")
        assertEquals("2026-11-09T09:00", next("RRULE:FREQ=DAILY;UNTIL=20261109", "2026-11-08T09:00"))
        assertNull(next("RRULE:FREQ=DAILY;UNTIL=20261109T000000Z", "2026-11-09T09:00"))
    }

    @Test
    fun keepsWallClockTimeAcrossDst() {
        // 2026-11-01 is the US fall-back day.
        assertEquals("2026-11-01T09:00", next("RRULE:FREQ=DAILY", "2026-10-31T09:00", la, 0))
        assertEquals("2026-11-02T09:00", next("RRULE:FREQ=DAILY", "2026-11-01T09:00", la, 0))
    }

    @Test
    fun supportedSubset() {
        assertTrue(Recurrence.isSupported("RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU"))
        assertTrue(Recurrence.isSupported("FREQ=MONTHLY;BYMONTHDAY=15;COUNT=4"))
        assertFalse(Recurrence.isSupported("RRULE:FREQ=MONTHLY;BYDAY=2MO"))
        assertFalse(Recurrence.isSupported("RRULE:FREQ=HOURLY"))
        assertFalse(Recurrence.isSupported("ERULE:NAME=LUNAR"))
        assertFalse(Recurrence.isSupported(""))
        assertNull(Recurrence.next("RRULE:FREQ=HOURLY", at("2026-11-08T09:00"), hcm, 0))
    }

    @Test
    fun describesRules() {
        assertEquals("Every day", Recurrence.describe("RRULE:FREQ=DAILY;INTERVAL=1"))
        assertEquals("Every 2 weeks on Mon, Fri", Recurrence.describe("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR"))
        assertEquals("Every month on day 31", Recurrence.describe("RRULE:FREQ=MONTHLY;BYMONTHDAY=31"))
        assertEquals("Repeats (custom)", Recurrence.describe("RRULE:FREQ=MONTHLY;BYDAY=2MO"))
    }
}

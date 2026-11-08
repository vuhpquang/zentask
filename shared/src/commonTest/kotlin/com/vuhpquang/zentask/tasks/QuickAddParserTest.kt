package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Project
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickAddParserTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private val now = LocalDateTime.parse("2026-11-08T10:00").toInstant(zone) // Sunday 10:00
    private val projects = listOf(
        Project(id = "inbox", name = "Inbox", isInbox = true),
        Project(id = "work", name = "🏢Works"),
        Project(id = "groc", name = "🛒Grocery"),
        Project(id = "viet", name = "Việc nhà"),
    )

    private fun p(text: String) = QuickAddParser.parse(text, now, zone, projects)

    @Test
    fun tagsPriorityAndList() {
        val q = p("Write spec #work #Q4/okr !high ^works")
        assertEquals("Write spec", q.title)
        assertEquals(listOf("work", "q4/okr"), q.tags)
        assertEquals(Priority.HIGH, q.priority)
        assertEquals("work", q.projectId)
        assertEquals(4, q.tokens.size)
    }

    @Test
    fun numericAndVietnamesePriority() {
        assertEquals(Priority.HIGH, p("a !1").priority)
        assertEquals(Priority.MEDIUM, p("a !2").priority)
        assertEquals(Priority.LOW, p("a !3").priority)
        assertEquals(Priority.HIGH, p("a !cao").priority)
        assertEquals(Priority.LOW, p("a !thấp").priority)
    }

    @Test
    fun listMatchIgnoresEmojiCaseAndDiacritics() {
        assertEquals("groc", p("Milk ^gro").projectId)
        assertEquals("viet", p("Lau nhà ^viec").projectId)
        val unknown = p("Milk ^nowhere")
        assertNull(unknown.projectId)
        assertEquals("Milk ^nowhere", unknown.title)
    }

    @Test
    fun symbolsInsideWordsAreNotTokens() {
        val q = p("C# notes wow! a^b x#y")
        assertEquals("C# notes wow! a^b x#y", q.title)
        assertTrue(q.tags.isEmpty())
        assertNull(q.priority)
        assertTrue(q.tokens.isEmpty())
    }

    @Test
    fun englishDates() {
        assertEquals(ms("2026-11-08T00:00"), p("Pay rent today").due)
        assertTrue(p("Pay rent today").allDay)
        assertEquals(ms("2026-11-09T00:00"), p("call mom tomorrow").due)
        assertEquals(ms("2026-11-13T00:00"), p("review fri").due)
        assertEquals(ms("2026-11-09T00:00"), p("plan next week").due, "next week = next Monday")
        assertEquals(ms("2026-11-11T00:00"), p("x in 3 days").due)
        val timed = p("standup tomorrow 9:30am")
        assertEquals(ms("2026-11-09T09:30"), timed.due)
        assertEquals(false, timed.allDay)
        assertEquals("standup", timed.title)
    }

    @Test
    fun vietnameseDates() {
        assertEquals(ms("2026-11-09T00:00"), p("Gọi ngân hàng ngày mai").due)
        assertEquals("Gọi ngân hàng", p("Gọi ngân hàng ngày mai").title)
        assertEquals(ms("2026-11-09T00:00"), p("Đi chợ mai").due)
        assertEquals(ms("2026-11-08T00:00"), p("Họp hôm nay").due)
        assertEquals(ms("2026-11-13T00:00"), p("Nộp báo cáo thứ 6").due)
        assertEquals(ms("2026-11-08T00:00"), p("Đi bơi chủ nhật").due)
        assertEquals(ms("2026-11-09T00:00"), p("Lên kế hoạch tuần sau").due)
        assertEquals(ms("2026-11-08T21:30"), p("Đọc sách 21h30").due)
        assertEquals(ms("2026-11-09T15:00"), p("Gặp khách mai 3h chiều").due)
        assertEquals(ms("2026-11-08T20:00"), p("Gọi mẹ 8h tối").due)
    }

    @Test
    fun vietnameseWordsThatFoldToEnglishStayText() {
        val q = p("Gửi thư cho sếp")
        assertNull(q.due)
        assertEquals("Gửi thư cho sếp", q.title)
    }

    @Test
    fun capitalisedMaiIsAName() {
        val q = p("Gửi quà cho Mai")
        assertNull(q.due)
        assertEquals("Gửi quà cho Mai", q.title)
    }

    @Test
    fun timeAloneMeansTodayOrTomorrowIfPast() {
        assertEquals(ms("2026-11-08T15:00"), p("gym 3pm").due)
        assertEquals(ms("2026-11-09T08:00"), p("gym 8am").due, "08:00 already passed today")
    }

    @Test
    fun emptyTitleKeepsRawText() {
        assertEquals("#work", p("#work").title)
    }
}

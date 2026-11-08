package com.vuhpquang.zentask.calendar

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class LunarTest {
    @Test
    fun knownVietnameseLunarDates() {
        // Values as TickTick shows them for the owner (and the Vietnamese calendar).
        assertEquals(Lunar.Date(30, 9, 2026, false), Lunar.of(LocalDate(2026, 11, 8)))
        assertEquals(Lunar.Date(1, 10, 2026, false), Lunar.of(LocalDate(2026, 11, 9)))
        assertEquals(Lunar.Date(1, 1, 2026, false), Lunar.of(LocalDate(2026, 2, 17))) // Tết Bính Ngọ
        assertEquals(Lunar.Date(1, 1, 2025, false), Lunar.of(LocalDate(2025, 1, 29))) // Tết Ất Tỵ
        assertEquals(Lunar.Date(1, 6, 2025, true), Lunar.of(LocalDate(2025, 7, 25))) // leap 6th month of 2025
        assertEquals(Lunar.Date(15, 8, 2026, false), Lunar.of(LocalDate(2026, 9, 25))) // Trung thu 2026
    }

    @Test
    fun shortLabelShowsMonthOnTheFirst() {
        assertEquals("1/10", Lunar.of(LocalDate(2026, 11, 9)).label)
        assertEquals("30", Lunar.of(LocalDate(2026, 11, 8)).label)
    }
}

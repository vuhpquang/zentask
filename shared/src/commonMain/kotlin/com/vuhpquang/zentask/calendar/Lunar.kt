package com.vuhpquang.zentask.calendar

import kotlinx.datetime.LocalDate
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Vietnamese lunar calendar (UTC+7), after Hồ Ngọc Đức's well-known astronomical algorithm: new moons and
 * the sun's longitude decide month starts, the 11th month holds the winter solstice, and a year with 13
 * months repeats the first month without a major solar term.
 */
object Lunar {
    data class Date(val day: Int, val month: Int, val year: Int, val leap: Boolean) {
        /** What a calendar cell shows: the day, or "day/month" on the first of a month. */
        val label: String get() = if (day == 1) "$day/$month" else "$day"
    }

    private const val TZ = 7.0

    fun of(date: LocalDate): Date {
        val dayNumber = jd(date.day, date.month.ordinal + 1, date.year)
        val k = floor((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = newMoonDay(k + 1)
        if (monthStart > dayNumber) monthStart = newMoonDay(k)
        var a11 = month11(date.year)
        var b11 = a11
        val lunarYear: Int
        if (a11 >= monthStart) {
            lunarYear = date.year
            a11 = month11(date.year - 1)
        } else {
            lunarYear = date.year + 1
            b11 = month11(date.year + 1)
        }
        val day = dayNumber - monthStart + 1
        val diff = floor((monthStart - a11) / 29.0).toInt()
        var leap = false
        var month = diff + 11
        if (b11 - a11 > 365) {
            val leapDiff = leapMonthOffset(a11)
            if (diff >= leapDiff) {
                month = diff + 10
                if (diff == leapDiff) leap = true
            }
        }
        if (month > 12) month -= 12
        val year = if (month >= 11 && diff < 4) lunarYear - 1 else lunarYear
        return Date(day, month, year, leap)
    }

    private fun jd(dd: Int, mm: Int, yy: Int): Int {
        val a = (14 - mm) / 12
        val y = yy + 4800 - a
        val m = mm + 12 * a - 3
        var j = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        if (j < 2299161) j = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083
        return j
    }

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        var jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd1 += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c1 = c1 - 0.4068 * sin(mpr * dr) + 0.0161 * sin(dr * 2 * mpr)
        c1 -= 0.0004 * sin(dr * 3 * mpr)
        c1 = c1 + 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr))
        c1 = c1 - 0.0074 * sin(dr * (m - mpr)) + 0.0004 * sin(dr * (2 * f + m))
        c1 = c1 - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mpr))
        c1 = c1 + 0.0010 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
        val deltaT = if (t < -11) {
            0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        } else {
            -0.000278 + 0.000265 * t + 0.000262 * t2
        }
        return jd1 + c1 - deltaT
    }

    private fun newMoonDay(k: Int): Int = floor(newMoon(k) + 0.5 + TZ / 24).toInt()

    private fun sunLongitude(jdn: Double): Double {
        val t = (jdn - 2451545.0) / 36525
        val t2 = t * t
        val dr = PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m)
        dl += (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.000290 * sin(dr * 3 * m)
        var l = (l0 + dl) * dr
        l -= PI * 2 * floor(l / (PI * 2))
        return l
    }

    /** The sun's longitude sector (0–11) at the start of a day. */
    private fun sunSector(dayNumber: Int): Int = floor(sunLongitude(dayNumber - 0.5 - TZ / 24) / PI * 6).toInt()

    /** First day of the 11th lunar month of [year] (the one holding the winter solstice). */
    private fun month11(year: Int): Int {
        val off = jd(31, 12, year) - 2415021
        val k = floor(off / 29.530588853).toInt()
        val nm = newMoonDay(k)
        return if (sunSector(nm) >= 9) newMoonDay(k - 1) else nm
    }

    private fun leapMonthOffset(a11: Int): Int {
        val k = floor((a11 - 2415021.076998695) / 29.530588853 + 0.5).toInt()
        var i = 1
        var arc = sunSector(newMoonDay(k + i))
        var last: Int
        do {
            last = arc
            i++
            arc = sunSector(newMoonDay(k + i))
        } while (arc != last && i < 14)
        return i - 1
    }
}

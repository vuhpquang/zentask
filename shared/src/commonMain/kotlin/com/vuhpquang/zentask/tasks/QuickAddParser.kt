package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Project
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What quick add recognised; [tokens] let the UI show chips for the recognised parts. */
data class QuickAdd(
    val title: String,
    val tags: List<String>,
    val priority: Int?,
    val projectId: String?,
    val due: Long?,
    val allDay: Boolean,
    val tokens: List<Token>,
) {
    enum class Kind { TAG, PRIORITY, LIST, DATE }

    /** [start] until [end] (exclusive) in the original text. */
    data class Token(val start: Int, val end: Int, val kind: Kind, val label: String)
}

/**
 * Quick add syntax (spec §11): `#tag`, `!high/!medium/!low/!none` (also `!1..!3`, `!cao/!vừa/!thấp`),
 * `^list` (prefix of a list name, ignoring emoji, case and diacritics), and dates in English or
 * Vietnamese ("tomorrow 9am", "mai 3h chiều", "thứ 6", "tuần sau"). A token must start a word, so
 * "C# notes" or "wow!" stay as written. Capitalised "Mai" is a name, not "tomorrow".
 */
object QuickAddParser {
    private val WORD = Regex("""\S+""")
    private val TAG = Regex("""^#([\p{L}\p{N}_\-/]+)$""")
    private val PRIORITY = mapOf(
        "high" to Priority.HIGH, "1" to Priority.HIGH, "cao" to Priority.HIGH,
        "medium" to Priority.MEDIUM, "med" to Priority.MEDIUM, "2" to Priority.MEDIUM, "vua" to Priority.MEDIUM,
        "low" to Priority.LOW, "3" to Priority.LOW, "thap" to Priority.LOW,
        "none" to Priority.NONE, "0" to Priority.NONE,
    )
    private val WEEKDAYS_EN = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val VI_DAY_NUMBER = mapOf(2 to DayOfWeek.MONDAY, 3 to DayOfWeek.TUESDAY, 4 to DayOfWeek.WEDNESDAY, 5 to DayOfWeek.THURSDAY, 6 to DayOfWeek.FRIDAY, 7 to DayOfWeek.SATURDAY)
    private val TIME_EN = Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)?$""")
    private val TIME_VI = Regex("""^(\d{1,2})h(\d{2})?$""")

    private class Word(val text: String, val start: Int, val end: Int) {
        val folded = fold(text.lowercase())
    }

    fun parse(text: String, now: Instant, zone: TimeZone, projects: List<Project>): QuickAdd {
        val words = WORD.findAll(text).map { Word(it.value, it.range.first, it.range.last + 1) }.toList()
        val tokens = mutableListOf<QuickAdd.Token>()
        val tags = mutableListOf<String>()
        var priority: Int? = null
        var projectId: String? = null
        val today = now.toLocalDateTime(zone).date
        var date: LocalDate? = null
        var time: LocalTime? = null

        var i = 0
        while (i < words.size) {
            val w = words[i]
            // #tag
            val tag = TAG.matchEntire(w.text)
            if (tag != null) {
                tags += tag.groupValues[1].lowercase()
                tokens += QuickAdd.Token(w.start, w.end, QuickAdd.Kind.TAG, tag.groupValues[1].lowercase())
                i++
                continue
            }
            // !priority
            if (w.text.startsWith("!") && w.text.length > 1) {
                PRIORITY[fold(w.text.drop(1).lowercase())]?.let { p ->
                    priority = p
                    tokens += QuickAdd.Token(w.start, w.end, QuickAdd.Kind.PRIORITY, w.text.drop(1))
                    i++
                    continue
                }
            }
            // ^list
            if (w.text.startsWith("^") && w.text.length > 1) {
                val query = fold(w.text.drop(1).lowercase())
                projects.firstOrNull { nameKey(it.name).startsWith(query) }?.let { p ->
                    projectId = p.id
                    tokens += QuickAdd.Token(w.start, w.end, QuickAdd.Kind.LIST, p.name)
                    i++
                    continue
                }
            }
            // dates and times (longest phrase first)
            val d = matchDate(words, i, today)
            if (d != null && date == null) {
                date = d.first
                tokens += QuickAdd.Token(words[i].start, words[i + d.second - 1].end, QuickAdd.Kind.DATE, words.subList(i, i + d.second).joinToString(" ") { it.text })
                i += d.second
                continue
            }
            val t = matchTime(words, i)
            if (t != null && time == null) {
                time = t.first
                tokens += QuickAdd.Token(words[i].start, words[i + t.second - 1].end, QuickAdd.Kind.DATE, words.subList(i, i + t.second).joinToString(" ") { it.text })
                i += t.second
                continue
            }
            i++
        }

        var due: Long? = null
        val allDay = time == null
        if (date != null || time != null) {
            var day = date ?: today
            if (date == null && time != null && LocalDateTime(day, time).toInstant(zone) <= now) day = day.plus(1, DateTimeUnit.DAY)
            due = if (time != null) LocalDateTime(day, time).toInstant(zone).toEpochMilliseconds() else day.atStartOfDayIn(zone).toEpochMilliseconds()
        }

        val title = buildString {
            var last = 0
            for (tok in tokens.sortedBy { it.start }) {
                append(text, last, tok.start)
                last = tok.end
            }
            append(text, last, text.length)
        }.replace(Regex("""\s+"""), " ").trim()

        return QuickAdd(
            title = title.ifEmpty { text.trim() },
            tags = tags.distinct(),
            priority = priority,
            projectId = projectId,
            due = due,
            allDay = allDay,
            tokens = tokens.sortedBy { it.start },
        )
    }

    /** A date phrase starting at word [i]: (date, words used). */
    private fun matchDate(words: List<Word>, i: Int, today: LocalDate): Pair<LocalDate, Int>? {
        fun f(k: Int) = words.getOrNull(i + k)?.folded
        val w0 = f(0) ?: return null
        val raw0 = words[i].text
        val lower0 = raw0.lowercase()
        // two/three-word phrases
        when ("$w0 ${f(1)}") {
            "hom nay" -> return today to 2
            "ngay mai" -> return today.plus(1, DateTimeUnit.DAY) to 2
            "ngay mot" -> return today.plus(2, DateTimeUnit.DAY) to 2
            "chu nhat" -> return nextWeekday(today, DayOfWeek.SUNDAY) to 2
            "tuan sau", "tuan toi", "next week" -> return mondayAfter(today) to 2
        }
        if (lower0 == "thứ" || lower0 == "thu") f(1)?.toIntOrNull()?.let { n -> VI_DAY_NUMBER[n]?.let { return nextWeekday(today, it) to 2 } }
        if (w0 == "in") {
            val n = f(1)?.toIntOrNull()
            when (f(2)) {
                "day", "days" -> if (n != null) return today.plus(n, DateTimeUnit.DAY) to 3
                "week", "weeks" -> if (n != null) return today.plus(7 * n, DateTimeUnit.DAY) to 3
            }
        }
        // one word
        return when {
            lower0 == "today" || lower0 == "hnay" -> today to 1
            lower0 == "tomorrow" || lower0 == "tmr" -> today.plus(1, DateTimeUnit.DAY) to 1
            raw0 == "mai" -> today.plus(1, DateTimeUnit.DAY) to 1 // lowercase only: "Mai" is a name
            raw0 == "mốt" -> today.plus(2, DateTimeUnit.DAY) to 1
            raw0 == "cn" || raw0 == "CN" -> nextWeekday(today, DayOfWeek.SUNDAY) to 1
            lower0.length == 2 && lower0[0] == 't' && lower0[1].isDigit() -> VI_DAY_NUMBER[lower0[1].digitToInt()]?.let { nextWeekday(today, it) to 1 }
            // English weekday names match the unfolded text: Vietnamese "thư" (letter) must not become Thursday.
            else -> WEEKDAYS_EN[lower0]?.let { nextWeekday(today, it) to 1 }
        }
    }

    /** A time at word [i] ("9am", "21:30", "9h30", "3h chiều"): (time, words used). */
    private fun matchTime(words: List<Word>, i: Int): Pair<LocalTime, Int>? {
        val w = words[i].folded
        TIME_EN.matchEntire(w)?.let { m ->
            val (h, mm, ampm) = m.destructured
            if (ampm.isEmpty() && mm.isEmpty()) return null // a bare number is not a time
            var hour = h.toInt()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return time(hour, mm.toIntOrNull() ?: 0)?.let { it to 1 }
        }
        TIME_VI.matchEntire(w)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val part = words.getOrNull(i + 1)?.folded
            val used = if (part in setOf("sang", "trua", "chieu", "toi", "dem")) 2 else 1
            if ((part == "chieu" || part == "toi") && hour < 12) hour += 12
            if (part == "trua" && hour < 6) hour += 12
            return time(hour, minute)?.let { it to used }
        }
        return null
    }

    private fun time(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime(h, m) else null

    /** [day] itself when it is that weekday (unless [strict]), else the next one. */
    private fun nextWeekday(from: LocalDate, day: DayOfWeek, strict: Boolean = false): LocalDate {
        val diff = (day.isoDayNumber - from.dayOfWeek.isoDayNumber + 7) % 7
        return from.plus(if (diff == 0 && strict) 7 else diff, DateTimeUnit.DAY)
    }

    private fun mondayAfter(today: LocalDate): LocalDate = nextWeekday(today, DayOfWeek.MONDAY, strict = true)

    /** List name for matching: lowercase, no diacritics, leading emoji/symbols dropped. */
    private fun nameKey(name: String): String = fold(name.lowercase()).dropWhile { !it.isLetterOrDigit() }

    private const val FROM = "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ"
    private const val TO = "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd"

    /** Removes Vietnamese diacritics (lowercase input). */
    fun fold(s: String): String = buildString(s.length) {
        for (c in s) {
            val k = FROM.indexOf(c)
            append(if (k >= 0) TO[k] else c)
        }
    }
}

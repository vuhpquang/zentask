package com.vuhpquang.zentask.server.calendar

import com.vuhpquang.zentask.tasks.Recurrence
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/** Downloads an iCal feed and expands it ([IcalParser]). `webcal://` links are fetched over https. */
class IcalHttpFetcher(private val http: HttpClient) : IcalFetcher {
    override suspend fun events(url: String, from: Long, until: Long): List<IcalEvent> {
        val res = http.get(url.replaceFirst("webcal://", "https://"))
        if (res.status.value !in 200..299) throw IllegalStateException("iCal feed: HTTP ${res.status.value}")
        return IcalParser.parse(res.bodyAsText(), from, until)
    }
}

/**
 * Minimal RFC 5545 reader for calendar feeds: VEVENT with DTSTART/DTEND (UTC, TZID, floating, or
 * all-day DATE), SUMMARY/LOCATION/DESCRIPTION, STATUS, RRULE (the shared Recurrence subset), EXDATE
 * and RECURRENCE-ID overrides. Unknown lines are ignored.
 */
object IcalParser {
    private val DEFAULT_ZONE = TimeZone.of("Asia/Ho_Chi_Minh")
    private const val MAX_OCCURRENCES = 2000

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private class Raw(val props: List<Prop>) {
        fun first(name: String) = props.firstOrNull { it.name == name }
        fun all(name: String) = props.filter { it.name == name }
    }

    private data class When(val millis: Long, val allDay: Boolean, val zone: String?)

    fun parse(text: String, from: Long, until: Long): List<IcalEvent> {
        val events = blocks(unfold(text))
        // RECURRENCE-ID overrides replace that occurrence of the series with the same UID.
        val overridden = HashMap<String, MutableSet<Long>>()
        for (e in events) {
            val uid = e.first("UID")?.value ?: continue
            e.first("RECURRENCE-ID")?.let { r -> time(r)?.let { overridden.getOrPut(uid) { mutableSetOf() } += it.millis } }
        }
        val out = mutableListOf<IcalEvent>()
        for (e in events) {
            if (e.first("STATUS")?.value.equals("CANCELLED", ignoreCase = true)) continue
            val uid = e.first("UID")?.value ?: continue
            val start = e.first("DTSTART")?.let(::time) ?: continue
            val end = e.first("DTEND")?.let(::time)
            val length = (end?.millis ?: (if (start.allDay) start.millis + 86_400_000L else start.millis)) - start.millis
            val base = IcalEvent(
                uid = uid, summary = unescape(e.first("SUMMARY")?.value.orEmpty()), location = unescape(e.first("LOCATION")?.value.orEmpty()),
                description = unescape(e.first("DESCRIPTION")?.value.orEmpty()), start = start.millis, end = start.millis + length,
                allDay = start.allDay, timeZone = start.zone,
            )
            val rule = e.first("RRULE")?.value
            if (rule == null || e.first("RECURRENCE-ID") != null) {
                if (base.end >= from && base.start <= until) out += base
                continue
            }
            val excluded = e.all("EXDATE").flatMap { p -> p.value.split(',').mapNotNull { v -> time(Prop("EXDATE", p.params, v))?.millis } }.toSet() +
                overridden[uid].orEmpty()
            val zone = start.zone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: DEFAULT_ZONE
            var occurrence: Long? = start.millis
            var i = 0
            while (occurrence != null && occurrence <= until && i < MAX_OCCURRENCES) {
                if (occurrence !in excluded && occurrence + length >= from) out += base.copy(start = occurrence, end = occurrence + length)
                occurrence = Recurrence.next(rule, Instant.fromEpochMilliseconds(occurrence), zone, i)?.toEpochMilliseconds()
                i++
            }
        }
        return out
    }

    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (line in text.split(Regex("\r\n|\n|\r"))) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += line.substring(1) else lines += line
        }
        return lines
    }

    private fun blocks(lines: List<String>): List<Raw> {
        val out = mutableListOf<Raw>()
        var current: MutableList<Prop>? = null
        var depth = 0
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { current = mutableListOf(); depth = 0 }
                line == "END:VEVENT" -> { current?.let { out += Raw(it) }; current = null }
                line.startsWith("BEGIN:") && current != null -> depth++ // e.g. VALARM inside an event
                line.startsWith("END:") && current != null -> depth--
                current != null && depth == 0 -> prop(line)?.let { current.add(it) }
            }
        }
        return out
    }

    private fun prop(line: String): Prop? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') } }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    /** `20261108` (all-day), `20261108T090000Z` (UTC), `…T090000` with TZID or floating. */
    private fun time(p: Prop): When? {
        val v = p.value.trim()
        val tzid = p.params["TZID"]
        return runCatching {
            if (p.params["VALUE"] == "DATE" || v.length == 8) {
                val d = LocalDate(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
                When(d.atStartOfDayIn(DEFAULT_ZONE).toEpochMilliseconds(), true, null)
            } else {
                val ldt = LocalDateTime(
                    v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt(),
                    v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt(),
                )
                val zone = when {
                    v.endsWith("Z") -> TimeZone.UTC
                    tzid != null -> runCatching { TimeZone.of(tzid) }.getOrNull() ?: DEFAULT_ZONE
                    else -> DEFAULT_ZONE
                }
                When(ldt.toInstant(zone).toEpochMilliseconds(), false, tzid)
            }
        }.getOrNull()
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    @Suppress("unused")
    private fun LocalDate.next(): LocalDate = plus(1, DateTimeUnit.DAY)
}

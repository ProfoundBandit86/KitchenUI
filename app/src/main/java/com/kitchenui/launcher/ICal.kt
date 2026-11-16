package com.kitchenui.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class CalEvent(val start: ZonedDateTime, val allDay: Boolean, val title: String)

/**
 * Minimal iCalendar reader for a Google Calendar "secret address in iCal format".
 * Handles single events, all-day events, time zones, EXDATE and simple RRULEs
 * (DAILY / WEEKLY with BYDAY / MONTHLY / YEARLY, INTERVAL, COUNT, UNTIL).
 */
object ICal {
    private val dtFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    suspend fun fetch(url: String, days: Long = 14): List<CalEvent> = withContext(Dispatchers.IO) {
        val u = url.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")
        parse(Net.get(u), days)
    }

    fun parse(text: String, days: Long): List<CalEvent> {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val windowStart = now.toLocalDate().atStartOfDay(zone)
        val windowEnd = windowStart.plusDays(days)
        val out = mutableListOf<CalEvent>()
        var inEvent = false
        var props = mutableListOf<Pair<String, String>>()
        for (line in unfold(text)) {
            when {
                line == "BEGIN:VEVENT" -> { inEvent = true; props = mutableListOf() }
                line == "END:VEVENT" -> {
                    if (inEvent) {
                        try { handle(props, zone, windowStart, windowEnd, out) } catch (_: Exception) { }
                    }
                    inEvent = false
                }
                inEvent -> {
                    val i = line.indexOf(':')
                    if (i > 0) props.add(line.substring(0, i) to line.substring(i + 1))
                }
            }
        }
        val cutoff = now.minusHours(1)
        return out
            .filter { if (it.allDay) !it.start.isBefore(windowStart) else it.start.isAfter(cutoff) }
            .distinctBy { it.title to it.start }
            .sortedBy { it.start }
    }

    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + raw.substring(1)
            } else {
                lines.add(raw)
            }
        }
        return lines
    }

    private fun unescape(s: String) =
        s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseDate(key: String, value: String, zone: ZoneId): Pair<ZonedDateTime, Boolean>? = try {
        val params = key.split(';').drop(1).associate {
            val kv = it.split('=', limit = 2)
            kv[0].uppercase() to (kv.getOrNull(1) ?: "")
        }
        val v = value.trim()
        if (v.length == 8 || params["VALUE"] == "DATE") {
            LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone) to true
        } else if (v.endsWith("Z")) {
            LocalDateTime.parse(v.dropLast(1), dtFormat).atZone(ZoneOffset.UTC).withZoneSameInstant(zone) to false
        } else {
            val tz = params["TZID"]?.let { runCatching { ZoneId.of(it.trim('"')) }.getOrNull() } ?: zone
            LocalDateTime.parse(v, dtFormat).atZone(tz).withZoneSameInstant(zone) to false
        }
    } catch (_: Exception) {
        null
    }

    private fun dayOf(s: String): DayOfWeek? = when (s.takeLast(2).uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun handle(
        props: List<Pair<String, String>>,
        zone: ZoneId,
        windowStart: ZonedDateTime,
        windowEnd: ZonedDateTime,
        out: MutableList<CalEvent>,
    ) {
        fun prop(name: String) = props.firstOrNull { it.first.substringBefore(';').equals(name, true) }
        if (prop("STATUS")?.second?.trim().equals("CANCELLED", true)) return
        val dt = prop("DTSTART") ?: return
        val (start, allDay) = parseDate(dt.first, dt.second, zone) ?: return
        val title = unescape(prop("SUMMARY")?.second ?: "(No title)").trim()
        val exdates = props
            .filter { it.first.substringBefore(';').equals("EXDATE", true) }
            .flatMap { p -> p.second.split(',').mapNotNull { parseDate(p.first, it, zone)?.first } }
            .toSet()
        val rrule = prop("RRULE")?.second

        fun inWindow(t: ZonedDateTime) = !t.isBefore(windowStart) && t.isBefore(windowEnd)

        if (rrule == null) {
            if (inWindow(start)) out.add(CalEvent(start, allDay, title))
            return
        }

        val r = rrule.split(';').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1] else null
        }.toMap()
        val freq = r["FREQ"]?.uppercase() ?: return
        val interval = (r["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { parseDate("UNTIL", it, zone)?.first }
        val byDay = r["BYDAY"]?.split(',')?.mapNotNull { dayOf(it) }

        var emitted = 0
        fun emit(t: ZonedDateTime): Boolean {
            if (until != null && t.isAfter(until)) return false
            if (count != null && emitted >= count) return false
            emitted++
            if (inWindow(t) && t !in exdates) out.add(CalEvent(t, allDay, title))
            return t.isBefore(windowEnd)
        }

        var i = 0L
        if (count == null) {
            val unit = when (freq) {
                "DAILY" -> ChronoUnit.DAYS
                "WEEKLY" -> ChronoUnit.WEEKS
                "MONTHLY" -> ChronoUnit.MONTHS
                "YEARLY" -> ChronoUnit.YEARS
                else -> null
            }
            if (unit != null) i = maxOf(0L, unit.between(start, windowStart) / interval - 1)
        }
        val firstMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var guard = 0
        while (guard++ < 3000) {
            val keepGoing = when (freq) {
                "DAILY" -> emit(start.plusDays(i * interval))
                "WEEKLY" -> {
                    val monday = firstMonday.plusWeeks(i * interval)
                    val days = if (byDay.isNullOrEmpty()) listOf(start.dayOfWeek) else byDay.sortedBy { it.value }
                    var ok = true
                    for (d in days) {
                        val t = monday.plusDays((d.value - 1).toLong())
                        if (t.isBefore(start)) continue
                        if (!emit(t)) { ok = false; break }
                    }
                    ok
                }
                "MONTHLY" -> emit(start.plusMonths(i * interval))
                "YEARLY" -> emit(start.plusYears(i * interval))
                else -> { emit(start); false }
            }
            if (!keepGoing) break
            i++
        }
    }
}

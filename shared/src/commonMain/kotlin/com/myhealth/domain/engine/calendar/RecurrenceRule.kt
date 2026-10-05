package com.myhealth.domain.engine.calendar

import com.myhealth.domain.util.basicIsoDate
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.parseBasicIsoDate
import com.myhealth.domain.util.parseIsoDate
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/** `FREQ` values this app supports (PLAN §2.2.4 — a deliberate subset of RFC 5545). */
enum class RecurrenceFreq { DAILY, WEEKLY }

/**
 * The RFC 5545 subset the calendar understands (PLAN §2.2.4, P3.1):
 * `FREQ=WEEKLY|DAILY;BYDAY=MO,TU,…;INTERVAL=n;UNTIL=yyyyMMdd;COUNT=n`.
 *
 * Anything outside the subset is ignored rather than rejected: an unparseable rule yields `null`
 * from [parse] and the event is then treated as a one-off, which is always safe to render.
 *
 * [untilDay] is an **inclusive** epoch day (`UNTIL` in RFC 5545 is inclusive); [count] caps the
 * number of occurrences counted from the series start, not from the queried window.
 */
data class RecurrenceRule(
    val freq: RecurrenceFreq,
    val byDay: Set<DayOfWeek> = emptySet(),
    val interval: Int = 1,
    val untilDay: Long? = null,
    val count: Int? = null,
) {

    /** Canonical `KEY=VALUE;…` form; `INTERVAL=1` is omitted because it is the default. */
    fun format(): String = buildList {
        add("FREQ=${freq.name}")
        if (byDay.isNotEmpty()) {
            add("BYDAY=" + byDay.sortedBy { it.isoDayNumber }.joinToString(",") { CODE_OF[it]!! })
        }
        if (interval != 1) add("INTERVAL=$interval")
        untilDay?.let { add("UNTIL=" + it.epochDayDate().basicIsoDate()) }
        count?.let { add("COUNT=$it") }
    }.joinToString(";")

    companion object {
        private val BY_CODE: Map<String, DayOfWeek> = mapOf(
            "MO" to DayOfWeek.MONDAY,
            "TU" to DayOfWeek.TUESDAY,
            "WE" to DayOfWeek.WEDNESDAY,
            "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY,
            "SA" to DayOfWeek.SATURDAY,
            "SU" to DayOfWeek.SUNDAY,
        )

        private val CODE_OF: Map<DayOfWeek, String> = BY_CODE.entries.associate { (k, v) -> v to k }

        /**
         * Parses [raw]; returns `null` for a blank string or a rule without a supported `FREQ`.
         * Keys and values are case-insensitive and surrounding whitespace is tolerated.
         */
        fun parse(raw: String?): RecurrenceRule? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(';')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) {
                        null
                    } else {
                        part.substring(0, idx).trim().uppercase() to
                            part.substring(idx + 1).trim()
                    }
                }
                .toMap()

            val freq = when (parts["FREQ"]?.uppercase()) {
                "WEEKLY" -> RecurrenceFreq.WEEKLY
                "DAILY" -> RecurrenceFreq.DAILY
                else -> return null
            }
            val byDay = parts["BYDAY"].orEmpty()
                .split(',')
                .mapNotNull { BY_CODE[it.trim().uppercase()] }
                .toSet()
            val interval = parts["INTERVAL"]?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val count = parts["COUNT"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            return RecurrenceRule(
                freq = freq,
                byDay = byDay,
                interval = interval,
                untilDay = parseUntil(parts["UNTIL"]),
                count = count,
            )
        }

        /** `yyyyMMdd` (RFC form) or the tolerated `yyyy-MM-dd`; an instant form keeps its date part. */
        private fun parseUntil(raw: String?): Long? {
            val text = raw?.trim()?.uppercase()?.substringBefore('T') ?: return null
            return when {
                text.contains('-') -> parseIsoDate(text)?.toEpochDays()
                text.length == 8 -> parseBasicIsoDate(text)?.toEpochDays()
                else -> null
            }
        }
    }
}

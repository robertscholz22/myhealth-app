package com.myhealth.domain.engine.calendar

import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.isoWeekStart
import com.myhealth.domain.util.plusDays
import com.myhealth.domain.util.plusWeeks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/**
 * Expands a (possibly recurring) `calendar_event` into [EventOccurrence]s (PLAN §2.2.4, P3.1).
 *
 * Pure `LocalDate` arithmetic — no zones, no instants — so a DST transition can never shift an
 * occurrence onto another local day. `INTERVAL` counts **ISO weeks from the week of the event's
 * own `startDay`** (Monday start), so a `INTERVAL=2` series keeps its parity regardless of which
 * window is queried. `UNTIL` (from the rule or the denormalized `recurrenceUntilDay`) is
 * inclusive, and `COUNT` is counted from the series start, not from [expand]'s window.
 *
 * Overrides are applied per base occurrence day: `SKIP` drops it, `MOVE` changes day/time (and,
 * when given, title/duration), `EDIT` changes title/time/duration in place. A `MOVE` may pull an
 * occurrence into the window from up to [MOVE_LOOKAHEAD_DAYS] days beyond it, or push one out.
 */
object RecurrenceExpander {

    /** Hard guard (P3.1): one call never returns more than this many occurrences. */
    const val MAX_OCCURRENCES = 1000

    /** Candidate days generated beyond `to`, so a `MOVE` backwards into the window still lands. */
    const val MOVE_LOOKAHEAD_DAYS = 31L

    /** Safety valve on candidate generation; far above any real series inside a rendered window. */
    private const val MAX_CANDIDATES = 20_000

    fun expand(
        event: CalendarEvent,
        overrides: List<EventOverride>,
        from: LocalDate,
        to: LocalDate,
    ): List<EventOccurrence> {
        if (to < from) return emptyList()
        val byDay = overrides.filter { it.eventId == event.id }.associateBy { it.occurrenceDay }
        val out = ArrayList<EventOccurrence>()
        for (day in candidateDays(event, to)) {
            val occurrence = apply(event, byDay[day.toEpochDays()], day) ?: continue
            val occurrenceDate = occurrence.occurrenceDay.epochDayDate()
            if (occurrenceDate < from || occurrenceDate > to) continue
            out += occurrence
            if (out.size >= MAX_OCCURRENCES) break
        }
        return out
    }

    /** Base (pre-override) occurrence days of the series, from its start up to the window end. */
    private fun candidateDays(event: CalendarEvent, to: LocalDate): List<LocalDate> {
        val start = event.startDay.epochDayDate()
        val rule = RecurrenceRule.parse(event.recurrenceRule)
            ?: return listOf(start)

        val until = listOfNotNull(rule.untilDay, event.recurrenceUntilDay).minOrNull()
        val generateTo = listOfNotNull(to.plusDays(MOVE_LOOKAHEAD_DAYS), until?.epochDayDate())
            .minOrNull()!!
        if (generateTo < start) return emptyList()

        val days = ArrayList<LocalDate>()
        val limit = rule.count ?: MAX_CANDIDATES
        when (rule.freq) {
            RecurrenceFreq.DAILY -> {
                var day = start
                while (day <= generateTo && days.size < limit && days.size < MAX_CANDIDATES) {
                    days += day
                    day = day.plusDays(rule.interval.toLong())
                }
            }

            RecurrenceFreq.WEEKLY -> {
                val weekdays = rule.byDay.ifEmpty { setOf(start.dayOfWeek) }.sortedBy { it.isoDayNumber }
                var weekStart = start.isoWeekStart()
                while (weekStart <= generateTo && days.size < limit &&
                    days.size < MAX_CANDIDATES
                ) {
                    for (weekday in weekdays) {
                        val day = weekStart.plusDays((weekday.isoDayNumber - 1).toLong())
                        if (day < start || day > generateTo) continue
                        days += day
                        if (days.size >= limit || days.size >= MAX_CANDIDATES) break
                    }
                    weekStart = weekStart.plusWeeks(rule.interval.toLong())
                }
            }
        }
        return days
    }

    /** Applies the override for [day] (if any); returns `null` when the occurrence is skipped. */
    private fun apply(
        event: CalendarEvent,
        override: EventOverride?,
        day: LocalDate,
    ): EventOccurrence? {
        val base = occurrenceOf(event, day.toEpochDays(), isOverride = false)
        if (override == null) return base
        val action = override.action.trim().uppercase()
        if (action == ACTION_SKIP) return null
        if (action != ACTION_MOVE && action != ACTION_EDIT) return base
        return base.copy(
            occurrenceDay = if (action == ACTION_MOVE) {
                override.newStartDay ?: base.occurrenceDay
            } else {
                base.occurrenceDay
            },
            effectiveTitle = override.newTitle ?: base.effectiveTitle,
            effectiveStartMinuteOfDay = override.newStartMinuteOfDay
                ?: base.effectiveStartMinuteOfDay,
            effectiveDurationMin = override.newDurationMin ?: base.effectiveDurationMin,
            isOverride = true,
        )
    }

    /**
     * The link on a `calendar_event` belongs to the series' own day: a per-occurrence link is
     * materialized as a child event (`parentEventId`, §2.2.4), so propagating the parent's link
     * to every occurrence would double-count it.
     */
    private fun occurrenceOf(event: CalendarEvent, day: Long, isOverride: Boolean) = EventOccurrence(
        eventId = event.id,
        occurrenceDay = day,
        type = event.type,
        effectiveTitle = event.title,
        effectiveStartMinuteOfDay = event.startMinuteOfDay,
        effectiveDurationMin = event.durationMin,
        isOverride = isOverride,
        linkedActivityId = event.linkedActivityId.takeIf { day == event.startDay },
        sportType = event.sportType,
        targetDistanceMeters = event.targetDistanceMeters,
        isKeyEvent = event.isKeyEvent,
    )

    private const val ACTION_SKIP = "SKIP"
    private const val ACTION_MOVE = "MOVE"
    private const val ACTION_EDIT = "EDIT"
}

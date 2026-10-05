package com.myhealth.data.repository

import com.myhealth.domain.engine.calendar.RecurrenceExpander
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.util.epochDayDate

/**
 * Builds the [CalendarDay] aggregate (PLAN §2.3, P3.2) out of the six calendar sources plus the
 * night's sleep. A **pure function over lists**: no Room, no flows, no dispatchers — the Room
 * plumbing lives in [RoomCalendarRepository], which is what makes this testable directly.
 *
 * Every day of `[fromDay, toDay]` is present in the result (empty days included, via
 * [CalendarDay.empty]), so the calendar grid never has to distinguish "no data" from "not loaded".
 * Recurring events are expanded here — SQL cannot evaluate a recurrence rule (§2.2.4).
 */
object CalendarAggregator {

    fun aggregate(
        fromDay: Long,
        toDay: Long,
        events: List<CalendarEvent> = emptyList(),
        overrides: List<EventOverride> = emptyList(),
        planned: List<PlannedSession> = emptyList(),
        activities: List<ActivitySummary> = emptyList(),
        meals: List<MealLogSummary> = emptyList(),
        targets: List<NutritionTarget> = emptyList(),
        loads: List<DailyLoad> = emptyList(),
        sleep: List<SleepRecord> = emptyList(),
    ): Map<Long, CalendarDay> {
        if (toDay < fromDay) return emptyMap()

        val occurrencesByDay = expand(events, overrides, fromDay, toDay).groupBy { it.occurrenceDay }
        val plannedByDay = planned.groupBy { it.day }
        val activitiesByDay = activities.groupBy { it.day }
        val mealsByDay = meals.groupBy { it.day }
        val targetByDay = targets.associateBy { it.day }
        val loadByDay = loads.associateBy { it.day }
        val sleepByNight = sleep.associateBy { it.night }

        val out = LinkedHashMap<Long, CalendarDay>()
        for (day in fromDay..toDay) {
            val dayMeals = mealsByDay[day].orEmpty().sortedWith(MEAL_ORDER)
            out[day] = CalendarDay(
                day = day,
                events = occurrencesByDay[day].orEmpty().sortedWith(OCCURRENCE_ORDER),
                planned = plannedByDay[day].orEmpty().sortedWith(PLANNED_ORDER),
                activities = activitiesByDay[day].orEmpty().sortedBy { it.startAtMillis },
                meals = dayMeals,
                target = targetByDay[day],
                intake = dayMeals.fold(MacroTotals.ZERO) { acc, meal -> acc + meal.totals },
                load = loadByDay[day],
                sleep = sleepByNight[day],
            )
        }
        return out
    }

    /** Expands every event once over the whole window (P3.1); one-off events yield ≤ 1 occurrence. */
    fun expand(
        events: List<CalendarEvent>,
        overrides: List<EventOverride>,
        fromDay: Long,
        toDay: Long,
    ): List<EventOccurrence> {
        if (toDay < fromDay) return emptyList()
        val overridesByEvent = overrides.groupBy { it.eventId }
        val from = fromDay.epochDayDate()
        val to = toDay.epochDayDate()
        return events.flatMap { event ->
            RecurrenceExpander.expand(event, overridesByEvent[event.id].orEmpty(), from, to)
        }
    }

    /** All-day entries first, then by start time, then by id — a stable, renderable order. */
    private val OCCURRENCE_ORDER = compareBy<EventOccurrence>(
        { it.effectiveStartMinuteOfDay ?: -1 },
        { it.eventId },
    )

    private val PLANNED_ORDER = compareBy<PlannedSession>(
        { it.startMinuteOfDay ?: -1 },
        { it.id },
    )

    private val MEAL_ORDER = compareBy<MealLogSummary>(
        { it.atMinuteOfDay ?: -1 },
        { it.id },
    )
}

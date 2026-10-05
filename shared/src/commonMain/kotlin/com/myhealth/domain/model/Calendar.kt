package com.myhealth.domain.model

/** Mirrors `calendar_event` (PLAN §2.2.4) — the recurring/definition row. */
data class CalendarEvent(
    val id: Long,
    val type: EventType,
    val title: String,
    val startDay: Long,
    val startMinuteOfDay: Int?,
    val durationMin: Int?,
    val location: String?,
    val sportType: SportType?,
    val targetDistanceMeters: Double?,
    val isKeyEvent: Boolean,
    val recurrenceRule: String?,
    val recurrenceUntilDay: Long?,
    val parentEventId: Long?,
    val linkedActivityId: Long?,
    val linkMethod: LinkMethod?,
    val notes: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Mirrors `event_override` — an exception to a recurring [CalendarEvent] (§2.2.4).
 * [action] is one of `"SKIP"`, `"MOVE"`, `"EDIT"` (kept as a plain string, matching the entity
 * column; not promoted to an enum since it is not part of the §2.1 enum table).
 */
data class EventOverride(
    val id: Long,
    val eventId: Long,
    val occurrenceDay: Long,
    val action: String,
    val newStartDay: Long?,
    val newStartMinuteOfDay: Int?,
    val newDurationMin: Int?,
    val newTitle: String?,
)

/**
 * Expanded instance of a (possibly recurring) [CalendarEvent] on a specific day (§2.3). Never
 * stored — produced by `domain/engine/calendar/RecurrenceExpander.kt`.
 */
data class EventOccurrence(
    val eventId: Long,
    val occurrenceDay: Long,
    val type: EventType,
    val effectiveTitle: String,
    val effectiveStartMinuteOfDay: Int?,
    val effectiveDurationMin: Int?,
    val isOverride: Boolean,
    val linkedActivityId: Long?,
    val sportType: SportType?,
    val targetDistanceMeters: Double?,
    val isKeyEvent: Boolean,
)

/**
 * Aggregate view object for one calendar day, built by `CalendarRepository` (§2.3). Never a table.
 *
 * [sleep] extends the §2.3 field list with the night attributed to this day, which the day-detail
 * screen (P3.5) shows as its own section; it is read from `sleep_session` by the same aggregate
 * query, so carrying it here avoids a second observer per day.
 */
data class CalendarDay(
    val day: Long,
    val events: List<EventOccurrence>,
    val planned: List<PlannedSession>,
    val activities: List<ActivitySummary>,
    val meals: List<MealLogSummary>,
    val target: NutritionTarget?,
    val intake: MacroTotals,
    val load: DailyLoad?,
    val sleep: SleepRecord? = null,
) {
    companion object {
        /** A day with nothing on it — the value every day in a queried range starts from. */
        fun empty(day: Long): CalendarDay = CalendarDay(
            day = day,
            events = emptyList(),
            planned = emptyList(),
            activities = emptyList(),
            meals = emptyList(),
            target = null,
            intake = MacroTotals.ZERO,
            load = null,
            sleep = null,
        )
    }
}

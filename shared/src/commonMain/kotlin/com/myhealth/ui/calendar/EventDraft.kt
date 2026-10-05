package com.myhealth.ui.calendar

import com.myhealth.resources.*
import com.myhealth.domain.engine.calendar.RecurrenceFreq
import com.myhealth.domain.engine.calendar.RecurrenceRule
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.UiMessage
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** `None` / `Weekly` recurrence toggle (§4.2 Event edit, P3.6) — "every n weeks" is the same
 * [RecurrenceMode.WEEKLY] mode with [EventDraft.recurrenceIntervalWeeks] above 1. */
enum class RecurrenceMode { NONE, WEEKLY }

/** Field identity for [validate] errors, mirrors `OnboardingField`'s pattern (§1.4). */
enum class EventField { TITLE, DATE, DURATION, DISTANCE, RECURRENCE_UNTIL, RECURRENCE_WEEKDAYS }

/**
 * The event editor's form state (§4.2 Event edit, P3.6), before it becomes a [CalendarEvent].
 * [targetDistanceKm] is entered in km and converted to metres on save — the form never shows the
 * user a metres figure for a race target.
 */
data class EventDraft(
    val id: Long = 0L,
    val type: EventType = EventType.APPOINTMENT,
    val title: String = "",
    val date: LocalDate? = null,
    val hasTime: Boolean = false,
    val startMinuteOfDay: Int? = null,
    val durationMin: Int? = null,
    val location: String = "",
    val sportType: SportType? = null,
    val targetDistanceKm: Double? = null,
    val isKeyEvent: Boolean = false,
    val notes: String = "",
    val recurrenceMode: RecurrenceMode = RecurrenceMode.NONE,
    val recurrenceWeekdays: Set<DayOfWeek> = emptySet(),
    val recurrenceIntervalWeeks: Int = 1,
    val recurrenceUntil: LocalDate? = null,
    val linkedActivityId: Long? = null,
    val linkMethod: LinkMethod? = null,
    val createdAtMillis: Long = 0L,
)

/** Whether [EventDraft.sportType] is shown/used for this event type (§4.2 Event edit). */
fun EventType.usesSportType(): Boolean =
    this == EventType.SOCCER_MATCH || this == EventType.SOCCER_TRAINING || this == EventType.RACE

/** Whether [EventDraft.targetDistanceKm] is shown/required for this event type. */
fun EventType.usesTargetDistance(): Boolean = this == EventType.RACE

/** A sensible default sport for the types that carry one, applied when the type changes. */
fun defaultSportTypeFor(type: EventType): SportType? = when (type) {
    EventType.SOCCER_MATCH -> SportType.SOCCER_MATCH
    EventType.SOCCER_TRAINING -> SportType.SOCCER_TRAINING
    EventType.RACE -> SportType.RUN_OUTDOOR
    else -> null
}

/**
 * Pure validation (unit-tested in `EventDraftValidationTest`, PLAN P3.6): empty title, duration
 * ≤ 0 when set, a recurrence `until` before the start date, a `RACE` with no target distance, and
 * a weekly recurrence with no weekday picked.
 */
fun validate(draft: EventDraft): Map<EventField, UiMessage> {
    val errors = mutableMapOf<EventField, UiMessage>()

    if (draft.title.isBlank()) {
        errors[EventField.TITLE] = UiMessage.of(Res.string.event_error_title_required)
    }
    if (draft.date == null) {
        errors[EventField.DATE] = UiMessage.of(Res.string.event_error_date_required)
    }

    val duration = draft.durationMin
    if (duration != null && duration <= 0) {
        errors[EventField.DURATION] = UiMessage.of(Res.string.event_error_duration_positive)
    }

    val until = draft.recurrenceUntil
    val start = draft.date
    if (until != null && start != null && until < start) {
        errors[EventField.RECURRENCE_UNTIL] = UiMessage.of(Res.string.event_error_recurrence_until_before_start)
    }

    if (draft.type == EventType.RACE) {
        val distance = draft.targetDistanceKm
        if (distance == null || distance <= 0.0) {
            errors[EventField.DISTANCE] = UiMessage.of(Res.string.event_error_distance_required)
        }
    }

    if (draft.recurrenceMode == RecurrenceMode.WEEKLY && draft.recurrenceWeekdays.isEmpty()) {
        errors[EventField.RECURRENCE_WEEKDAYS] = UiMessage.of(Res.string.event_error_recurrence_weekday_required)
    }

    return errors
}

/**
 * Builds the §2.2.4 RFC-5545-subset [RecurrenceRule] from the recurrence fields, or `null` for
 * [RecurrenceMode.NONE] / an incomplete weekly rule (no weekday — [validate] flags that
 * separately, so the draft itself never crashes trying to format it). Unit-tested in
 * `EventDraftRecurrenceTest`.
 */
fun EventDraft.recurrenceRule(): RecurrenceRule? {
    if (recurrenceMode == RecurrenceMode.NONE || recurrenceWeekdays.isEmpty()) return null
    return RecurrenceRule(
        freq = RecurrenceFreq.WEEKLY,
        byDay = recurrenceWeekdays.toSet(),
        interval = recurrenceIntervalWeeks.coerceAtLeast(1),
        untilDay = recurrenceUntil?.toEpochDays(),
    )
}

/** Builds the [CalendarEvent] to upsert. `createdAtMillis`/`updatedAtMillis`/`recurrenceUntilDay`
 * are re-derived by `CalendarRepository.upsertEvent` when left at their new-event defaults. */
fun EventDraft.toCalendarEvent(clock: PlatformClock): CalendarEvent {
    val rule = recurrenceRule()
    val usesSport = type.usesSportType()
    return CalendarEvent(
        id = id,
        type = type,
        title = title.trim(),
        startDay = requireNotNull(date) { "date must be validated before save" }.toEpochDays(),
        startMinuteOfDay = if (hasTime) startMinuteOfDay else null,
        durationMin = durationMin,
        location = location.trim().ifBlank { null },
        sportType = if (usesSport) sportType ?: defaultSportTypeFor(type) else null,
        targetDistanceMeters = if (type.usesTargetDistance()) targetDistanceKm?.times(1000.0) else null,
        isKeyEvent = isKeyEvent,
        recurrenceRule = rule?.format(),
        recurrenceUntilDay = rule?.untilDay,
        parentEventId = null,
        linkedActivityId = linkedActivityId,
        linkMethod = linkMethod,
        notes = notes.trim().ifBlank { null },
        createdAtMillis = createdAtMillis,
        updatedAtMillis = clock.millis(),
    )
}

/** Rebuilds the editor draft from a loaded [CalendarEvent] (P3.6: editing an existing event). */
fun eventDraftFrom(event: CalendarEvent): EventDraft {
    val rule = RecurrenceRule.parse(event.recurrenceRule)
    val isWeekly = rule != null && rule.freq == RecurrenceFreq.WEEKLY
    return EventDraft(
        id = event.id,
        type = event.type,
        title = event.title,
        date = event.startDay.epochDayDate(),
        hasTime = event.startMinuteOfDay != null,
        startMinuteOfDay = event.startMinuteOfDay,
        durationMin = event.durationMin,
        location = event.location.orEmpty(),
        sportType = event.sportType,
        targetDistanceKm = event.targetDistanceMeters?.div(1000.0),
        isKeyEvent = event.isKeyEvent,
        notes = event.notes.orEmpty(),
        recurrenceMode = if (isWeekly) RecurrenceMode.WEEKLY else RecurrenceMode.NONE,
        recurrenceWeekdays = if (isWeekly) rule.byDay.toSet() else emptySet(),
        recurrenceIntervalWeeks = if (isWeekly) rule.interval else 1,
        recurrenceUntil = (rule?.untilDay ?: event.recurrenceUntilDay)?.epochDayDate(),
        linkedActivityId = event.linkedActivityId,
        linkMethod = event.linkMethod,
        createdAtMillis = event.createdAtMillis,
    )
}

/** A fresh draft for a new event, seeded on the day the user was looking at (§4.1: the Calendar
 * FAB and Day detail "add" both pass the shown day). */
fun newEventDraft(seedDay: LocalDate): EventDraft = EventDraft(date = seedDay)

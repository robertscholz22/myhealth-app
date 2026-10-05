package com.myhealth.domain.engine.calendar

import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures

/** Builders for the calendar tests (PLAN P3.1/P3.3) — every date is an ISO string, UTC. */
internal object CalendarFixtures {

    const val NOW: Long = 1_757_000_000_000L

    fun event(
        id: Long = 1L,
        startIso: String,
        type: EventType = EventType.SOCCER_TRAINING,
        title: String = "Training",
        startMinuteOfDay: Int? = 18 * 60,
        durationMin: Int? = 90,
        sportType: SportType? = SportType.SOCCER_TRAINING,
        targetDistanceMeters: Double? = null,
        isKeyEvent: Boolean = false,
        recurrenceRule: String? = null,
        recurrenceUntilDay: Long? = null,
        linkedActivityId: Long? = null,
    ): CalendarEvent = CalendarEvent(
        id = id,
        type = type,
        title = title,
        startDay = Fixtures.epochDay(startIso),
        startMinuteOfDay = startMinuteOfDay,
        durationMin = durationMin,
        location = null,
        sportType = sportType,
        targetDistanceMeters = targetDistanceMeters,
        isKeyEvent = isKeyEvent,
        recurrenceRule = recurrenceRule,
        recurrenceUntilDay = recurrenceUntilDay,
        parentEventId = null,
        linkedActivityId = linkedActivityId,
        linkMethod = null,
        notes = null,
        createdAtMillis = NOW,
        updatedAtMillis = NOW,
    )

    fun override(
        id: Long = 1L,
        eventId: Long = 1L,
        occurrenceIso: String,
        action: String,
        newStartIso: String? = null,
        newStartMinuteOfDay: Int? = null,
        newDurationMin: Int? = null,
        newTitle: String? = null,
    ): EventOverride = EventOverride(
        id = id,
        eventId = eventId,
        occurrenceDay = Fixtures.epochDay(occurrenceIso),
        action = action,
        newStartDay = newStartIso?.let { Fixtures.epochDay(it) },
        newStartMinuteOfDay = newStartMinuteOfDay,
        newDurationMin = newDurationMin,
        newTitle = newTitle,
    )

    /** An [ActivitySummary] as the list queries return it; [startIso] is a UTC instant. */
    fun activity(
        id: Long,
        startIso: String,
        durationMin: Int,
        sportType: SportType = SportType.SOCCER_TRAINING,
        title: String? = null,
    ): ActivitySummary {
        val start = Fixtures.millis(startIso)
        return ActivitySummary(
            id = id,
            startAtMillis = start,
            endAtMillis = start + durationMin * 60_000L,
            day = Fixtures.epochDay(startIso.substringBefore('T')),
            sportType = sportType,
            sportGroup = sportType.group,
            title = title,
            durationSec = durationMin * 60,
            elapsedSec = durationMin * 60,
            distanceMeters = null,
            activeEnergyKcal = null,
            totalEnergyKcal = null,
            avgHr = null,
            maxHr = null,
            avgSpeedMps = null,
            maxSpeedMps = null,
            avgCadenceSpm = null,
            elevationGainM = null,
            trimp = null,
            loadMethod = null,
            rpe = null,
            note = null,
            primarySource = ActivitySource.HEALTH_CONNECT,
            mergedSources = listOf(ActivitySource.HEALTH_CONNECT),
            hasStreams = false,
        )
    }
}

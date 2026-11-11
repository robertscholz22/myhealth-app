package com.myhealth.ui.training

import com.myhealth.domain.engine.suggest.SessionCatalog
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [weeklyLoadSums] — the pure helper behind the week board's planned-vs-target-vs-actual bar
 * (PLAN §4.2 "Training plan", P6.6).
 */
class WeeklyLoadBarTest {

    private val monday = Fixtures.epochDay("2026-09-14")

    @Test
    fun sums_only_outstanding_planned_load_and_all_recorded_load() {
        val sums = weeklyLoadSums(
            planned = listOf(
                session(id = 1L, trimp = 120.0, status = PlannedStatus.PLANNED),
                session(id = 2L, trimp = 54.0, status = PlannedStatus.PLANNED),
                // Already done: its load is in `actual`, counting it again would double it.
                session(id = 3L, trimp = 99.0, status = PlannedStatus.COMPLETED),
                // Skipped sessions will never happen, and a session may carry no estimate at all.
                session(id = 4L, trimp = 80.0, status = PlannedStatus.SKIPPED),
                session(id = 5L, trimp = null, status = PlannedStatus.PLANNED),
            ),
            target = 620.0,
            actual = listOf(load(monday, 99.0), load(monday + 1, 41.5), load(monday + 2, 0.0)),
        )

        assertThat(sums.planned).isWithin(1e-9).of(174.0)
        assertThat(sums.target).isWithin(1e-9).of(620.0)
        assertThat(sums.actual).isWithin(1e-9).of(140.5)
        assertThat(sums.onTarget).isFalse()
    }

    @Test
    fun fixed_events_still_to_come_count_as_planned_load() {
        fun event(day: Long, type: EventType, minutes: Int?, linked: Long? = null) = EventOccurrence(
            eventId = day, occurrenceDay = day, type = type, effectiveTitle = "x",
            effectiveStartMinuteOfDay = null, effectiveDurationMin = minutes, isOverride = false,
            linkedActivityId = linked, sportType = null, targetDistanceMeters = null, isKeyEvent = false,
        )
        val match = SessionCatalog.estTrimpForEvent(EventType.SOCCER_MATCH, 90)!!
        val practice = SessionCatalog.estTrimpForEvent(EventType.SOCCER_TRAINING, null)!!
        assertThat(match).isGreaterThan(0.0)

        val sums = weeklyLoadSums(
            planned = listOf(session(id = 1L, trimp = 50.0, status = PlannedStatus.PLANNED)),
            target = 300.0,
            actual = emptyList(),
            events = listOf(
                event(monday, EventType.SOCCER_TRAINING, 90),          // before today: not outstanding
                event(monday + 2, EventType.SOCCER_MATCH, 90),
                event(monday + 3, EventType.SOCCER_TRAINING, null),    // no length: the catalog default
                event(monday + 4, EventType.SOCCER_MATCH, 90, linked = 7L), // played: in `actual`
                event(monday + 5, EventType.APPOINTMENT, 60),          // not training
            ),
            today = monday + 1,
        )

        assertThat(sums.planned).isWithin(1e-9).of(50.0 + match + practice)
    }

    @Test
    fun bars_are_scaled_to_the_largest_of_the_three() {
        val sums = weeklyLoadSums(
            planned = listOf(session(id = 1L, trimp = 200.0, status = PlannedStatus.PLANNED)),
            target = 400.0,
            actual = listOf(load(monday, 500.0)),
        )

        assertThat(sums.scale).isWithin(1e-9).of(500.0)
        assertThat(sums.plannedFraction).isWithin(1e-6f).of(0.4f)
        assertThat(sums.targetFraction).isWithin(1e-6f).of(0.8f)
        assertThat(sums.actualFraction).isWithin(1e-6f).of(1.0f)
        assertThat(sums.onTarget).isTrue()

        // An empty week divides by the 1.0 floor instead of by zero.
        val empty = weeklyLoadSums(planned = emptyList(), target = 0.0, actual = emptyList())
        assertThat(empty.scale).isWithin(1e-9).of(1.0)
        assertThat(empty.plannedFraction).isWithin(1e-6f).of(0f)
        assertThat(empty.onTarget).isFalse()
    }

    private fun session(id: Long, trimp: Double?, status: PlannedStatus): PlannedSession =
        PlannedSession(
            id = id,
            planId = 1L,
            day = monday,
            startMinuteOfDay = null,
            sportType = SportType.RUN_OUTDOOR,
            sessionType = SessionType.EASY_RUN,
            intensity = Intensity.LOW,
            targetDurationMin = 45,
            targetDistanceMeters = null,
            targetPaceSecPerKm = null,
            estimatedTrimp = trimp,
            description = null,
            rationale = null,
            status = status,
            locked = false,
            linkedActivityId = null,
            sourceSuggestionId = null,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

    private fun load(day: Long, trimp: Double): DailyLoad = DailyLoad(
        day = day,
        trimp = trimp,
        sessionCount = if (trimp > 0.0) 1 else 0,
        atl = 0.0,
        ctl = 0.0,
        acwr = null,
        tsb = 0.0,
        monotony = null,
        strain = null,
        recoveryScore = null,
        recoveryBand = null,
        recoveryConfidence = 1.0,
        flags = emptyList(),
        computedAtMillis = 0L,
    )
}

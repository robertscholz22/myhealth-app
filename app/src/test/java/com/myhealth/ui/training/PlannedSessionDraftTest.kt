package com.myhealth.ui.training

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlinx.datetime.LocalDate

/**
 * [validatePlannedSession] and the draft ⇄ domain conversion of the planned-session editor
 * (PLAN §4.2 "Planned session edit", P6.8).
 */
class PlannedSessionDraftTest {

    private val clock = Fixtures.fixedClock("2026-09-14T08:00:00Z")
    private val day = LocalDate(2026, 9, 15)

    @Test
    fun a_duration_that_is_set_must_be_positive() {
        // Not set at all is fine — a session may be "an easy run", no numbers attached.
        assertThat(validatePlannedSession(PlannedSessionDraft(day = day))).isEmpty()

        val zero = validatePlannedSession(PlannedSessionDraft(day = day, durationMin = 0))
        assertThat(zero).containsKey(PlannedSessionField.DURATION)

        val negative = validatePlannedSession(PlannedSessionDraft(day = day, durationMin = -10))
        assertThat(negative).containsKey(PlannedSessionField.DURATION)

        assertThat(validatePlannedSession(PlannedSessionDraft(day = day, durationMin = 45))).isEmpty()
    }

    @Test
    fun pace_must_be_between_two_and_fifteen_minutes_per_km() {
        val tooFast = validatePlannedSession(
            PlannedSessionDraft(day = day, paceMinutes = 1, paceSeconds = 59),
        )
        assertThat(tooFast).containsKey(PlannedSessionField.PACE)

        val tooSlow = validatePlannedSession(
            PlannedSessionDraft(day = day, paceMinutes = 15, paceSeconds = 1),
        )
        assertThat(tooSlow).containsKey(PlannedSessionField.PACE)

        val badSeconds = validatePlannedSession(
            PlannedSessionDraft(day = day, paceMinutes = 5, paceSeconds = 75),
        )
        assertThat(badSeconds[PlannedSessionField.PACE]).isEqualTo("Seconds must be 0–59.")

        // Both boundaries are allowed.
        assertThat(validatePlannedSession(PlannedSessionDraft(day = day, paceMinutes = 2))).isEmpty()
        assertThat(validatePlannedSession(PlannedSessionDraft(day = day, paceMinutes = 15))).isEmpty()
        assertThat(
            validatePlannedSession(PlannedSessionDraft(day = day, paceMinutes = 4, paceSeconds = 45)),
        ).isEmpty()
    }

    @Test
    fun the_draft_round_trips_through_the_domain_session() {
        val draft = PlannedSessionDraft(
            planId = 7L,
            day = day,
            startMinuteOfDay = 18 * 60,
            sportType = SportType.RUN_OUTDOOR,
            sessionType = SessionType.TEMPO_RUN,
            intensity = Intensity.HIGH,
            durationMin = 50,
            distanceKm = 10.0,
            paceMinutes = 4,
            paceSeconds = 45,
            description = "  4 x 8 min at threshold  ",
            locked = true,
        )

        val session = draft.toPlannedSession(clock)

        assertThat(session.day).isEqualTo(day.toEpochDays())
        assertThat(session.targetDistanceMeters).isWithin(1e-9).of(10_000.0)
        assertThat(session.targetPaceSecPerKm).isEqualTo(285)
        assertThat(session.description).isEqualTo("4 x 8 min at threshold")
        assertThat(session.locked).isTrue()
        // 0.30 * rpe 7.0 * 50 min (the §3.5.4 catalog row for TEMPO_RUN).
        assertThat(session.estimatedTrimp).isWithin(1e-9).of(105.0)
        assertThat(session.createdAtMillis).isEqualTo(clock.millis())

        val reloaded = plannedSessionDraftOf(session)
        assertThat(reloaded.distanceKm).isWithin(1e-9).of(10.0)
        assertThat(reloaded.paceMinutes).isEqualTo(4)
        assertThat(reloaded.paceSeconds).isEqualTo(45)
        assertThat(reloaded.sessionType).isEqualTo(SessionType.TEMPO_RUN)
        assertThat(reloaded.startMinuteOfDay).isEqualTo(18 * 60)

        // The dropdown only offers types that belong to the chosen sport.
        assertThat(sessionTypesFor(SportType.RUN_OUTDOOR)).contains(SessionType.LONG_RUN)
        assertThat(sessionTypesFor(SportType.RUN_OUTDOOR)).doesNotContain(SessionType.STRENGTH_FULL)
        assertThat(sessionTypesFor(SportType.STRENGTH)).contains(SessionType.STRENGTH_LOWER)
    }
}

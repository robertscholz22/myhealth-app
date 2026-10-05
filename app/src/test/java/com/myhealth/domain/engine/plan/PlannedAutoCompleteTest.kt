package com.myhealth.domain.engine.plan

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [PlannedAutoCompleter] (PLAN P6.8): a planned session is ticked off only by an activity that is
 * unambiguously the same session — right sport, right day, close enough in time and duration.
 */
class PlannedAutoCompleteTest {

    private val zone = Fixtures.TZ
    private val day = Fixtures.epochDay("2026-09-14")

    @Test
    fun matching_activity_completes_the_planned_session() {
        val session = plannedSession(id = 1L, sportType = SportType.RUN_OUTDOOR, durationMin = 45)
        val activity = activity(id = 10L, sportType = SportType.RUN_OUTDOOR, durationSec = 45 * 60)

        val completions = PlannedAutoCompleter.complete(listOf(session), listOf(activity), zone)

        assertThat(completions).hasSize(1)
        assertThat(completions.single().sessionId).isEqualTo(1L)
        assertThat(completions.single().activityId).isEqualTo(10L)
        // Same sport (0.5), no planned clock time (0.3), exact duration (0.2).
        assertThat(completions.single().confidence).isWithin(1e-6).of(1.0)
    }

    @Test
    fun wrong_sport_never_completes_a_session() {
        val session = plannedSession(id = 1L, sportType = SportType.RUN_OUTDOOR, durationMin = 45)
        val activity = activity(id = 10L, sportType = SportType.CYCLING, durationSec = 45 * 60)

        val completions = PlannedAutoCompleter.complete(listOf(session), listOf(activity), zone)

        assertThat(completions).isEmpty()
        // 0.3 (time) + 0.2 (duration) = 0.5, below the 0.7 bar however good the rest of the fit is.
        assertThat(PlannedAutoCompleter.confidence(session, activity, zone)).isWithin(1e-6).of(0.5)
    }

    @Test
    fun two_candidates_pick_the_closest_duration() {
        val session = plannedSession(id = 1L, sportType = SportType.RUN_OUTDOOR, durationMin = 60)
        val short = activity(id = 10L, sportType = SportType.RUN_OUTDOOR, durationSec = 20 * 60)
        val close = activity(id = 11L, sportType = SportType.RUN_OUTDOOR, durationSec = 58 * 60)

        val completions = PlannedAutoCompleter.complete(listOf(session), listOf(short, close), zone)

        assertThat(completions).hasSize(1)
        assertThat(completions.single().activityId).isEqualTo(11L)
        assertThat(PlannedAutoCompleter.confidence(session, close, zone))
            .isGreaterThan(PlannedAutoCompleter.confidence(session, short, zone))
    }

    @Test
    fun an_already_completed_session_is_left_untouched() {
        val done = plannedSession(id = 1L, sportType = SportType.RUN_OUTDOOR, durationMin = 45)
            .copy(status = PlannedStatus.COMPLETED, linkedActivityId = 9L)
        val open = plannedSession(id = 2L, sportType = SportType.RUN_OUTDOOR, durationMin = 45)
        val activity = activity(id = 10L, sportType = SportType.RUN_OUTDOOR, durationSec = 45 * 60)

        val completions = PlannedAutoCompleter.complete(listOf(done, open), listOf(activity), zone)

        assertThat(completions.map { it.sessionId }).containsExactly(2L)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun plannedSession(
        id: Long,
        sportType: SportType,
        durationMin: Int?,
        startMinuteOfDay: Int? = null,
    ): PlannedSession = PlannedSession(
        id = id,
        planId = 1L,
        day = day,
        startMinuteOfDay = startMinuteOfDay,
        sportType = sportType,
        sessionType = SessionType.EASY_RUN,
        intensity = Intensity.LOW,
        targetDurationMin = durationMin,
        targetDistanceMeters = null,
        targetPaceSecPerKm = null,
        estimatedTrimp = 54.0,
        description = null,
        rationale = null,
        status = PlannedStatus.PLANNED,
        locked = false,
        linkedActivityId = null,
        sourceSuggestionId = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun activity(
        id: Long,
        sportType: SportType,
        durationSec: Int,
        startIso: String = "2026-09-14T18:00:00Z",
    ): ActivitySummary {
        val start = Fixtures.millis(startIso)
        return ActivitySummary(
            id = id,
            startAtMillis = start,
            endAtMillis = start + durationSec * 1000L,
            day = day,
            sportType = sportType,
            sportGroup = sportType.group,
            title = null,
            durationSec = durationSec,
            elapsedSec = durationSec,
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

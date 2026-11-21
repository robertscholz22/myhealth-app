package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.5: the estimate activity and its replacement by a recording. */
class VideoDoneTest {

    private val day = 20_000L
    private val now = day * 86_400_000L + 18 * 3_600_000L

    @Test
    fun estimate_today_ends_now_and_carries_the_effort_as_rpe() {
        val a = VideoDone.estimateActivity("Core 20", StrengthWorkoutKind.CORE, day, 25, VideoEffort.HARD, now, day, TimeZone.UTC)
        assertEquals(now, a.endAtMillis)
        assertEquals(now - 25 * 60_000L, a.startAtMillis)
        assertEquals(1500, a.durationSec)
        assertEquals(7, a.rpe)
        assertEquals(SportType.STRENGTH, a.sportType)
        assertEquals(ActivitySource.MANUAL, a.primarySource)
        assertEquals(listOf(ActivitySource.MANUAL), a.mergedSources)
        assertNull(a.avgHr)
        assertEquals("Core 20", a.title)
    }

    @Test
    fun estimate_on_an_earlier_day_starts_at_noon() {
        val a = VideoDone.estimateActivity("x", StrengthWorkoutKind.FULL, day - 2, 30, VideoEffort.EASY, now, day, TimeZone.UTC)
        assertEquals((day - 2) * 86_400_000L + 12 * 3_600_000L, a.startAtMillis)
        assertEquals(day - 2, a.day)
        assertEquals(3, a.rpe)
    }

    @Test
    fun mobility_kinds_are_mobility_activities() {
        val a = VideoDone.estimateActivity("x", StrengthWorkoutKind.MOBILITY_FULL, day, 15, VideoEffort.EASY, now, day, TimeZone.UTC)
        assertEquals(SportType.MOBILITY, a.sportType)
    }

    @Test
    fun effort_scale() {
        assertEquals(listOf(3, 5, 7, 9), VideoEffort.entries.map { it.rpe })
    }

    @Test
    fun only_manual_only_activities_are_estimates() {
        assertTrue(VideoDone.isEstimate(summary(1, ActivitySource.MANUAL)))
        assertFalse(VideoDone.isEstimate(summary(2, ActivitySource.HEALTH_CONNECT)))
        assertFalse(VideoDone.isEstimate(summary(3, ActivitySource.HEALTH_CONNECT, merged = listOf(ActivitySource.HEALTH_CONNECT, ActivitySource.MANUAL))))
    }

    @Test
    fun a_recording_of_the_same_day_replaces_the_estimate() {
        val sessions = listOf(session(10, linked = 1))
        val activities = listOf(summary(1, ActivitySource.MANUAL), summary(2, ActivitySource.HEALTH_CONNECT, sport = SportType.HIIT))
        assertEquals(listOf(EstimateReplacement(10, 1, 2)), VideoDone.replacements(sessions, activities))
    }

    @Test
    fun no_replacement_by_another_day_another_sport_or_a_claimed_recording() {
        val sessions = listOf(session(10, linked = 1), session(11, linked = 4))
        val activities = listOf(
            summary(1, ActivitySource.MANUAL),
            summary(2, ActivitySource.HEALTH_CONNECT, dayOf = day - 1),
            summary(3, ActivitySource.HEALTH_CONNECT, sport = SportType.RUN_OUTDOOR),
            summary(4, ActivitySource.HEALTH_CONNECT),
        )
        assertTrue(VideoDone.replacements(sessions, activities).isEmpty())
    }

    @Test
    fun a_planned_or_recorded_session_is_left_alone() {
        val activities = listOf(summary(1, ActivitySource.MANUAL), summary(2, ActivitySource.HEALTH_CONNECT))
        assertTrue(VideoDone.replacements(listOf(session(10, linked = 1, status = PlannedStatus.PLANNED)), activities).isEmpty())
        assertTrue(VideoDone.replacements(listOf(session(10, linked = 2)), activities).isEmpty())
    }

    @Test
    fun one_recording_replaces_one_estimate_longest_first() {
        val sessions = listOf(session(10, linked = 1), session(11, linked = 2))
        val activities = listOf(
            summary(1, ActivitySource.MANUAL),
            summary(2, ActivitySource.MANUAL),
            summary(3, ActivitySource.HEALTH_CONNECT, durationSec = 1200),
            summary(4, ActivitySource.HEALTH_CONNECT, durationSec = 2400),
        )
        assertEquals(
            listOf(EstimateReplacement(10, 1, 4), EstimateReplacement(11, 2, 3)),
            VideoDone.replacements(sessions, activities),
        )
    }

    @Test
    fun mobility_estimates_need_a_mobility_recording() {
        val sessions = listOf(session(10, linked = 1))
        val strength = listOf(summary(1, ActivitySource.MANUAL, sport = SportType.MOBILITY), summary(2, ActivitySource.HEALTH_CONNECT))
        assertTrue(VideoDone.replacements(sessions, strength).isEmpty())
        val mobility = listOf(summary(1, ActivitySource.MANUAL, sport = SportType.MOBILITY), summary(3, ActivitySource.HEALTH_CONNECT, sport = SportType.MOBILITY))
        assertEquals(listOf(EstimateReplacement(10, 1, 3)), VideoDone.replacements(sessions, mobility))
    }

    private fun session(id: Long, linked: Long?, status: PlannedStatus = PlannedStatus.COMPLETED) = PlannedSession(
        id = id, planId = null, day = day, startMinuteOfDay = null, sportType = SportType.STRENGTH,
        sessionType = SessionType.STRENGTH_FULL, intensity = Intensity.MODERATE, targetDurationMin = 30,
        targetDistanceMeters = null, targetPaceSecPerKm = null, estimatedTrimp = null, description = null,
        rationale = null, status = status, locked = false, linkedActivityId = linked, sourceSuggestionId = null,
        createdAtMillis = 0L, updatedAtMillis = 0L, workoutId = 1L,
    )

    private fun summary(
        id: Long,
        source: ActivitySource,
        sport: SportType = SportType.STRENGTH,
        dayOf: Long = day,
        durationSec: Int = 1800,
        merged: List<ActivitySource> = listOf(source),
    ) = ActivitySummary(
        id = id, startAtMillis = 0L, endAtMillis = 0L, day = dayOf, sportType = sport, sportGroup = sport.group,
        title = null, durationSec = durationSec, elapsedSec = durationSec, distanceMeters = null,
        activeEnergyKcal = null, totalEnergyKcal = null, avgHr = null, maxHr = null, avgSpeedMps = null,
        maxSpeedMps = null, avgCadenceSpm = null, elevationGainM = null, trimp = null, loadMethod = null,
        rpe = null, note = null, primarySource = source, mergedSources = merged, hasStreams = false,
    )
}

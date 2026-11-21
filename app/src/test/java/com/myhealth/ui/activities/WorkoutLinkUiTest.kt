package com.myhealth.ui.activities

import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.5 "Which workout was this?" visibility. */
class WorkoutLinkUiTest {

    private val workouts = listOf(workout(1, "Video B"), workout(2, "a workout"))

    @Test
    fun unclaimed_strength_and_mobility_recordings_can_be_linked() {
        assertTrue(workoutLinkUi(5, SportType.STRENGTH, emptyList(), workouts, false).canLink)
        assertTrue(workoutLinkUi(5, SportType.HIIT, emptyList(), workouts, false).canLink)
        assertTrue(workoutLinkUi(5, SportType.MOBILITY, emptyList(), workouts, false).canLink)
        assertFalse(workoutLinkUi(5, SportType.RUN_OUTDOOR, emptyList(), workouts, false).canLink)
        assertFalse(workoutLinkUi(5, null, emptyList(), workouts, false).canLink)
    }

    @Test
    fun a_claimed_recording_shows_its_workout_instead() {
        val ui = workoutLinkUi(5, SportType.STRENGTH, listOf(session(linked = 5, workoutId = 1)), workouts, false)
        assertFalse(ui.canLink)
        assertEquals("Video B", ui.linkedWorkoutName)
        val other = workoutLinkUi(5, SportType.STRENGTH, listOf(session(linked = 6, workoutId = 1)), workouts, false)
        assertTrue(other.canLink)
        assertNull(other.linkedWorkoutName)
    }

    @Test
    fun workouts_are_listed_by_name() {
        assertEquals(listOf("a workout", "Video B"), workoutLinkUi(5, SportType.STRENGTH, emptyList(), workouts, true).workouts.map { it.name })
    }

    private fun workout(id: Long, name: String) = StrengthWorkout(
        id = id, name = name, kind = StrengthWorkoutKind.FULL, templateId = null, isBuiltIn = false,
        exercises = emptyList(), createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    private fun session(linked: Long, workoutId: Long) = PlannedSession(
        id = 1L, planId = null, day = 1L, startMinuteOfDay = null, sportType = SportType.STRENGTH,
        sessionType = SessionType.STRENGTH_FULL, intensity = Intensity.MODERATE, targetDurationMin = 30,
        targetDistanceMeters = null, targetPaceSecPerKm = null, estimatedTrimp = null, description = null,
        rationale = null, status = PlannedStatus.COMPLETED, locked = false, linkedActivityId = linked,
        sourceSuggestionId = null, createdAtMillis = 0L, updatedAtMillis = 0L, workoutId = workoutId,
    )
}

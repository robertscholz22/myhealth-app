package com.myhealth.domain.ai

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P28: the snapshot the relay's tests run against (`relay/test/fixtures/snapshot.json`), built by the
 * real [AiSnapshotBuilder] from made-up data, so the TypeScript side reads exactly what the phone
 * sends. `AI_FIXTURE_OUT=1` rewrites the file; otherwise the test checks it is current.
 */
class AiRelayFixture {

    @Test
    fun relay_fixture_matches_the_builder() {
        val json = snapshot().toJson()
        val file = listOf(File("relay/test/fixtures/snapshot.json"), File("../relay/test/fixtures/snapshot.json"))
            .first { it.parentFile.parentFile.isDirectory }
        if (System.getenv("AI_FIXTURE_OUT") != null) {
            file.parentFile.mkdirs()
            file.writeText(json + "\n")
        }
        assertTrue("relay fixture is stale: run with AI_FIXTURE_OUT=1", file.isFile && file.readText().trim() == json)
    }

    companion object {
        val TODAY: Long = LocalDate(2026, 12, 1).toEpochDays()

        fun session(id: Long, day: Long, type: SessionType, sport: SportType, minutes: Int, locked: Boolean = false, status: PlannedStatus = PlannedStatus.PLANNED, workoutId: Long? = null) =
            PlannedSession(
                id = id, planId = null, day = day, startMinuteOfDay = null, sportType = sport, sessionType = type,
                intensity = Intensity.LOW, targetDurationMin = minutes, targetDistanceMeters = null, targetPaceSecPerKm = null,
                estimatedTrimp = minutes * 1.2, description = null, rationale = null, status = status, locked = locked,
                linkedActivityId = null, sourceSuggestionId = null, createdAtMillis = 1L, updatedAtMillis = 1L, workoutId = workoutId,
            )

        fun snapshot(): AiSnapshot {
            val mine = Exercise("USER_TOWELROW01", "Towel row", setOf(MuscleGroup.LATS), setOf(MuscleGroup.BICEPS), Equipment.BODYWEIGHT, MovementPattern.HORIZONTAL_PULL, false, false, "")
            val owner = StrengthWorkout(
                id = 1, name = "My full body", kind = StrengthWorkoutKind.FULL, createdAtMillis = 1L, updatedAtMillis = 1L,
                exercises = listOf(
                    StrengthWorkoutExercise(1, 1, 0, "GOBLET_SQUAT", 3, reps = 10, loadKg = 16.0),
                    StrengthWorkoutExercise(2, 1, 1, "PUSH_UP", 3, reps = 12),
                    StrengthWorkoutExercise(3, 1, 2, "PLANK", 3, seconds = 40),
                ),
            )
            val event = CalendarEvent(
                id = 5, type = EventType.entries.first(), title = "Team training", startDay = TODAY + 2, startMinuteOfDay = 19 * 60,
                durationMin = 90, location = null, sportType = SportType.SOCCER_TRAINING, targetDistanceMeters = null, isKeyEvent = false,
                recurrenceRule = null, recurrenceUntilDay = null, parentEventId = null, linkedActivityId = null, linkMethod = null,
                notes = null, createdAtMillis = 1L, updatedAtMillis = 1L, intensity = Intensity.MODERATE,
            )
            return AiSnapshotBuilder.build(
                AiSnapshotInput(
                    nowMillis = 1_796_100_000_000L,
                    today = TODAY,
                    groups = AiDataGroup.ALL,
                    exercises = ExerciseCatalog.ALL + mine,
                    ownExerciseIds = setOf(mine.id),
                    equipment = setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BAND),
                    sports = listOf("RUN_OUTDOOR", "SOCCER_TRAINING", "STRENGTH"),
                    workouts = listOf(owner),
                    goals = emptyList(),
                    sessions = listOf(
                        session(9, TODAY - 2, SessionType.EASY_RUN, SportType.RUN_OUTDOOR, 40, status = PlannedStatus.COMPLETED),
                        session(10, TODAY + 1, SessionType.EASY_RUN, SportType.RUN_OUTDOOR, 45),
                        session(11, TODAY + 4, SessionType.LONG_RUN, SportType.RUN_OUTDOOR, 80, locked = true),
                        session(12, TODAY + 3, SessionType.STRENGTH_FULL, SportType.STRENGTH, 45, workoutId = 1),
                    ),
                    events = listOf(event),
                    activities = emptyList(),
                    activityLoad = emptyMap(),
                    links = emptyList(),
                    dailyLoads = listOf(DailyLoad(TODAY - 1, 60.0, 1, 48.0, 42.0, 1.14, -6.0, null, null, 70, null, 1.0, emptyList(), 1L)),
                    body = AiBodyDto(age = 35, sex = "MALE", restingHr = 50, maxHr = 188),
                    cycle = CycleStatus(
                        day = TODAY, dayOfCycle = 10, phase = CyclePhase.FOLLICULAR, isLateLuteal = false, isPredicted = true,
                        cycleLengthDays = 28, periodLengthDays = 5, nextPeriodStart = TODAY + 18, ovulationDay = TODAY + 4,
                        fertileWindow = (TODAY - 1)..(TODAY + 5), confidence = CycleConfidence.LOW,
                    ),
                ),
            )
        }
    }
}

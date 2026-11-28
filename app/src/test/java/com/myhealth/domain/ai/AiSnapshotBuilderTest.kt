package com.myhealth.domain.ai

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.PredictedCycle
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSnapshotBuilderTest {

    private val today = LocalDate(2026, 12, 1).toEpochDays()

    private fun session(id: Long, day: Long, locked: Boolean = false, status: PlannedStatus = PlannedStatus.PLANNED) = PlannedSession(
        id = id, planId = null, day = day, startMinuteOfDay = 18 * 60 + 5, sportType = SportType.RUN_OUTDOOR,
        sessionType = SessionType.EASY_RUN, intensity = Intensity.LOW, targetDurationMin = 45, targetDistanceMeters = 8_456.0,
        targetPaceSecPerKm = 325, estimatedTrimp = 54.04, description = null, rationale = null, status = status,
        locked = locked, linkedActivityId = null, sourceSuggestionId = null, createdAtMillis = 1L, updatedAtMillis = 1L,
    )

    private val mine = Exercise("USER_mine0001", "Towel row", setOf(MuscleGroup.LATS), emptySet(), Equipment.BODYWEIGHT, MovementPattern.HORIZONTAL_PULL, false, false, "")
    private val byAi = Exercise("USER_ai000001", "Wall sit pulse", setOf(MuscleGroup.QUADS), emptySet(), Equipment.BODYWEIGHT, MovementPattern.SQUAT, false, true, "")

    private val cycle = CycleStatus(
        day = today, dayOfCycle = 20, phase = CyclePhase.LUTEAL, isLateLuteal = false, isPredicted = true,
        cycleLengthDays = 28, periodLengthDays = 5, nextPeriodStart = today + 9, ovulationDay = today - 6,
        fertileWindow = (today - 11)..(today - 5), confidence = CycleConfidence.MEDIUM,
    )

    private fun input(groups: Set<AiDataGroup>) = AiSnapshotInput(
        nowMillis = 42L,
        today = today,
        groups = groups,
        exercises = ExerciseCatalog.ALL + mine + byAi,
        ownExerciseIds = setOf(mine.id, byAi.id),
        equipment = setOf(Equipment.DUMBBELL, Equipment.BODYWEIGHT),
        sports = listOf("RUN_OUTDOOR", "SOCCER_TRAINING"),
        workouts = listOf(
            StrengthWorkout(
                id = 3, name = "Home A", kind = StrengthWorkoutKind.FULL, createdAtMillis = 1L, updatedAtMillis = 1L,
                exercises = listOf(
                    StrengthWorkoutExercise(2, 3, 1, "PLANK", 3, seconds = 40),
                    StrengthWorkoutExercise(1, 3, 0, "PUSH_UP", 3, reps = 10),
                ),
            ),
        ),
        goals = emptyList(),
        sessions = listOf(
            session(1, today - 40), session(2, today + 1), session(3, today + 2, locked = true),
            session(4, today - 1), session(5, today + 200),
        ),
        events = emptyList(),
        activities = emptyList(),
        activityLoad = emptyMap(),
        links = listOf(AiLink("ai-ex1", AiKind.EXERCISE, byAi.id, 1L), AiLink("ai-s2", AiKind.SESSION, "2", 1L), AiLink("ai-w3", AiKind.WORKOUT, "3", 1L)),
        dailyLoads = listOf(DailyLoad(today - 1, 80.0, 1, 60.0, 55.123, 1.0912, 0.0, null, null, 72, null, 1.0, emptyList(), 1L)),
        body = AiBodyDto(age = 38, restingHr = 52),
        cycle = cycle,
        predictedCycles = listOf(PredictedCycle(today + 9, today + 13, today + 23, (today + 18)..(today + 24))),
    )

    @Test
    fun sn01_basics_only_leaves_the_optional_groups_out() {
        val s = AiSnapshotBuilder.build(input(emptySet()))
        assertEquals(listOf("BASICS"), s.groups)
        assertNull(s.load)
        assertNull(s.body)
        assertNull(s.cycle)
        val json = Json.parseToJsonElement(s.toJson()).jsonObject
        assertFalse("load" in json)
        assertFalse("body" in json)
        assertFalse("cycle" in json)
    }

    @Test
    fun sn02_all_groups_fill_load_body_and_cycle() {
        val s = AiSnapshotBuilder.build(input(AiDataGroup.ALL))
        assertEquals(listOf("BASICS", "LOAD", "BODY", "CYCLE"), s.groups)
        val day = s.load!!.days.single()
        assertEquals("2026-11-30", day.day)
        assertEquals(55.1, day.fitness, 0.0)
        assertEquals(60.0, day.fatigue, 0.0)
        assertEquals(1.09, day.acwr!!, 0.0)
        assertEquals(38, s.body!!.age)
        assertEquals("LUTEAL", s.cycle!!.phase)
        assertEquals("2026-12-10", s.cycle!!.nextPeriodStart)
        assertEquals(listOf(AiWindow("2026-12-10", "2026-12-14")), s.cycle!!.periodsAhead)
    }

    @Test
    fun sn03_sessions_in_the_window_with_flags_and_formats() {
        val s = AiSnapshotBuilder.build(input(emptySet()))
        assertEquals(listOf("s4", "s2", "s3"), s.sessions.map { it.id })
        val s2 = s.sessions.first { it.id == "s2" }
        assertEquals("2026-12-02", s2.day)
        assertEquals("18:05", s2.start)
        assertEquals("5:25", s2.pace)
        assertEquals(8.46, s2.distanceKm!!, 0.0)
        assertEquals(54.0, s2.loadAu!!, 0.0)
        assertTrue(s2.changeable)
        assertTrue(s2.byAi)
        assertFalse(s.sessions.first { it.id == "s3" }.changeable)
        assertFalse(s.sessions.first { it.id == "s4" }.changeable)
        assertEquals("2026-11-03", s.window.from)
        assertEquals("2027-02-23", s.window.to)
    }

    @Test
    fun sn04_exercises_are_tagged_by_source_and_links_map_to_snapshot_ids() {
        val s = AiSnapshotBuilder.build(input(emptySet()))
        assertEquals("catalog", s.exercises.first { it.id == "PUSH_UP" }.source)
        assertEquals("mine", s.exercises.first { it.id == mine.id }.source)
        assertEquals("ai", s.exercises.first { it.id == byAi.id }.source)
        assertEquals(mapOf("ai-ex1" to byAi.id, "ai-s2" to "s2", "ai-w3" to "w3"), s.links)
    }

    @Test
    fun sn05_workouts_list_rows_in_order() {
        val w = AiSnapshotBuilder.build(input(emptySet())).workouts.single()
        assertEquals("w3", w.id)
        assertEquals(listOf("PUSH_UP", "PLANK"), w.rows.map { it.exerciseId })
        assertTrue(w.byAi)
        assertTrue(w.minutes > 0)
        assertEquals(listOf("BODYWEIGHT", "DUMBBELL"), AiSnapshotBuilder.build(input(emptySet())).equipment)
    }

    @Test
    fun sn06_changeable_matches_the_planner() {
        val state = AiLocalState(
            today = today, library = com.myhealth.domain.engine.strength.ExerciseLibrary(), workouts = emptyMap(),
            sessions = input(emptySet()).sessions.associateBy { it.id }, links = emptyList(), equipment = emptySet(),
        )
        for (s in state.sessions.values) {
            val planner = AiOpPlanner.plan(AiOp.DeleteSession("o", AiIds.session(s.id)), state, 1L) is AiDecision.Apply
            assertEquals("session ${s.id}", AiSnapshotBuilder.isChangeable(s, today), planner)
        }
        assertNotNull(AiSnapshotBuilder.build(input(emptySet())).toJson())
    }
}

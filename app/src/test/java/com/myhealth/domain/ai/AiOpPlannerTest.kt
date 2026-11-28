package com.myhealth.domain.ai

import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Equipment
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
import com.myhealth.domain.model.UserExercise
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AiOpPlannerTest {

    private val today = LocalDate(2026, 12, 1).toEpochDays()
    private val now = 1_800_000_000_000L

    private val myExercise = UserExercise(
        Exercise("USER_mine0001", "Towel row", setOf(MuscleGroup.LATS), emptySet(), Equipment.BODYWEIGHT, MovementPattern.HORIZONTAL_PULL, false, false, ""),
        null, false, 1L, 1L,
    )
    private val aiExercise = UserExercise(
        Exercise("USER_ai000001", "Wall sit pulse", setOf(MuscleGroup.QUADS), emptySet(), Equipment.BODYWEIGHT, MovementPattern.SQUAT, false, true, ""),
        null, false, 1L, 1L,
    )
    private val archived = UserExercise(
        Exercise("USER_old00001", "Old thing", setOf(MuscleGroup.ABS), emptySet(), Equipment.BODYWEIGHT, MovementPattern.CORE, false, false, ""),
        null, true, 1L, 1L,
    )

    private fun workout(id: Long, name: String) = StrengthWorkout(
        id = id, name = name, kind = StrengthWorkoutKind.FULL, createdAtMillis = 1L, updatedAtMillis = 1L,
        exercises = listOf(StrengthWorkoutExercise(id * 10, id, 0, "PUSH_UP", 3, reps = 10)),
    )

    private fun session(id: Long, day: Long = today + 2, locked: Boolean = false, status: PlannedStatus = PlannedStatus.PLANNED, workoutId: Long? = null) =
        PlannedSession(
            id = id, planId = null, day = day, startMinuteOfDay = null, sportType = SportType.RUN_OUTDOOR,
            sessionType = SessionType.EASY_RUN, intensity = Intensity.LOW, targetDurationMin = 45, targetDistanceMeters = null,
            targetPaceSecPerKm = null, estimatedTrimp = 54.0, description = "easy", rationale = null, status = status,
            locked = locked, linkedActivityId = null, sourceSuggestionId = null, createdAtMillis = 1L, updatedAtMillis = 1L,
            workoutId = workoutId,
        )

    private val state = AiLocalState(
        today = today,
        library = ExerciseLibrary(listOf(myExercise, aiExercise, archived)),
        workouts = mapOf(1L to workout(1, "My full body"), 2L to workout(2, "AI full body")),
        sessions = mapOf(
            10L to session(10),
            11L to session(11, locked = true),
            12L to session(12, status = PlannedStatus.COMPLETED),
            13L to session(13, day = today - 1),
            14L to session(14, workoutId = 2),
        ),
        links = listOf(
            AiLink("ai-ex1", AiKind.EXERCISE, "USER_ai000001", 1L),
            AiLink("ai-w2", AiKind.WORKOUT, "2", 1L),
            AiLink("ai-s14", AiKind.SESSION, "14", 1L),
        ),
        equipment = setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL),
    )

    private fun plan(op: AiOp) = AiOpPlanner.plan(op, state, now, Random(1))

    private fun step(op: AiOp): AiStep {
        val d = plan(op)
        assertTrue("expected Apply, got $d", d is AiDecision.Apply)
        return (d as AiDecision.Apply).step
    }

    private fun reason(op: AiOp): String {
        val d = plan(op)
        assertTrue("expected Reject, got $d", d is AiDecision.Reject)
        return (d as AiDecision.Reject).reason
    }

    private fun row(id: String, sets: Int = 3, reps: Int? = 10, seconds: Int? = null) = AiWorkoutRow(id, sets, reps, seconds)

    // --- exercises -------------------------------------------------------------------------------

    @Test
    fun op01_create_exercise_makes_an_own_exercise() {
        val s = step(AiOp.CreateExercise("o1", "ai-ex9", "Side-lying hip pulse", listOf("adductors", "obliques"), equipment = "bodyweight", pattern = "CORE", timed = true)) as AiStep.InsertExercise
        assertEquals("ai-ex9", s.aiId)
        assertTrue(s.exercise.exercise.id.startsWith("USER_"))
        assertEquals(setOf(MuscleGroup.ADDUCTORS, MuscleGroup.OBLIQUES), s.exercise.exercise.primary)
        assertTrue(s.exercise.exercise.isTimed)
        assertEquals(now, s.exercise.createdAtMillis)
    }

    @Test
    fun op02_create_exercise_refuses_duplicates_unknown_muscles_and_bad_ids() {
        assertTrue(reason(AiOp.CreateExercise("o", "ai-x", "push-UP", listOf("CHEST"))).contains("PUSH_UP"))
        assertTrue(reason(AiOp.CreateExercise("o", "ai-x", "Thing", listOf("BICEPZ"))).contains("Unknown muscle"))
        assertTrue(reason(AiOp.CreateExercise("o", "ai-x", "Thing", emptyList())).contains("primary"))
        assertTrue(reason(AiOp.CreateExercise("o", "ai-x", "Thing", listOf("CHEST"), equipment = "SPACESHIP")).contains("equipment"))
        assertTrue(reason(AiOp.CreateExercise("o", "x-1", "Thing", listOf("CHEST"))).contains("ai-"))
        assertTrue(reason(AiOp.CreateExercise("o", "ai-x", " ", listOf("CHEST"))).contains("name"))
    }

    @Test
    fun op03_secondary_muscles_drop_the_primary_ones() {
        val s = step(AiOp.CreateExercise("o", "ai-x", "Thing", listOf("CHEST"), secondary = listOf("CHEST", "TRICEPS"))) as AiStep.InsertExercise
        assertEquals(setOf(MuscleGroup.TRICEPS), s.exercise.exercise.secondary)
    }

    @Test
    fun op04_a_reused_ai_id_counts_as_already_applied() {
        assertEquals(AiDecision.AlreadyApplied, plan(AiOp.CreateExercise("o", "ai-ex1", "Other name", listOf("CHEST"))))
        assertEquals(AiDecision.AlreadyApplied, plan(AiOp.ScheduleSession("o", "ai-s14", AiSessionFields(day = "2026-12-03", sport = "WALK"))))
    }

    @Test
    fun op05_set_equipment_keeps_bodyweight_and_the_before_value() {
        val s = step(AiOp.SetEquipment("o", listOf("KETTLEBELL", "band"))) as AiStep.PutEquipment
        assertEquals(setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL), s.before)
        assertEquals(setOf(Equipment.BODYWEIGHT, Equipment.KETTLEBELL, Equipment.BAND), s.after)
        assertTrue(reason(AiOp.SetEquipment("o", listOf("ROWER"))).contains("ROWER"))
    }

    // --- workouts --------------------------------------------------------------------------------

    @Test
    fun op06_create_workout_resolves_catalog_own_and_ai_exercises() {
        val s = step(
            AiOp.CreateWorkout("o", "ai-w9", "Home A", "full", listOf(row("PUSH_UP"), row("USER_mine0001"), row("ai-ex1", reps = null, seconds = 40), row("GOBLET_SQUAT").copy(loadKg = 16.0))),
        ) as AiStep.InsertWorkout
        val w = s.workout
        assertEquals(StrengthWorkoutKind.FULL, w.kind)
        assertEquals(listOf("PUSH_UP", "USER_mine0001", "USER_ai000001", "GOBLET_SQUAT"), w.exercises.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2, 3), w.exercises.map { it.orderIndex })
        assertTrue(w.exercises[0].isBodyweight)
        assertEquals(false, w.exercises[3].isBodyweight)
        assertEquals(false, w.useInSuggestions)
        assertEquals(0L, w.id)
    }

    @Test
    fun op07_workout_rows_are_checked() {
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = emptyList())).contains("1 to"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("NOPE")))).contains("Unknown exercise"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("USER_old00001")))).contains("Unknown exercise"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("PUSH_UP", sets = 0)))).contains("sets"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("PUSH_UP", seconds = 30)))).contains("not both"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("PUSH_UP", reps = null)))).contains("reps"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("PLANK")))).contains("timed"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", rows = listOf(row("PUSH_UP").copy(loadKg = -1.0)))).contains("load"))
        assertTrue(reason(AiOp.CreateWorkout("o", "ai-w", "X", kind = "YOGA", rows = listOf(row("PUSH_UP")))).contains("kind"))
    }

    @Test
    fun op08_ai_may_update_its_own_workout_only() {
        val s = step(AiOp.UpdateWorkout("o", "ai-w2", name = "AI full body v2", rows = listOf(row("PLANK", reps = null, seconds = 45)))) as AiStep.ReplaceWorkout
        assertEquals("AI full body", s.before.name)
        assertEquals("AI full body v2", s.after.name)
        assertEquals(2L, s.after.exercises.single().workoutId)
        assertEquals(now, s.after.updatedAtMillis)
        // By snapshot id too.
        assertTrue(step(AiOp.UpdateWorkout("o", "w2", notes = "n")) is AiStep.ReplaceWorkout)
        assertTrue(reason(AiOp.UpdateWorkout("o", "w1", name = "Hijack")).contains("not a workout the AI created"))
        assertTrue(reason(AiOp.UpdateWorkout("o", "w99", name = "x")).contains("No workout"))
    }

    @Test
    fun op09_update_without_rows_keeps_rows() {
        val s = step(AiOp.UpdateWorkout("o", "ai-w2", name = "Renamed")) as AiStep.ReplaceWorkout
        assertEquals(s.before.exercises, s.after.exercises)
    }

    @Test
    fun op10_delete_workout_needs_ai_ownership_and_no_sessions_using_it() {
        assertTrue(reason(AiOp.DeleteWorkout("o", "w1")).contains("not a workout the AI created"))
        assertTrue(reason(AiOp.DeleteWorkout("o", "ai-w2")).contains("s14"))
        val free = state.copy(sessions = state.sessions - 14L)
        val d = AiOpPlanner.plan(AiOp.DeleteWorkout("o", "ai-w2"), free, now)
        assertTrue(d is AiDecision.Apply && d.step is AiStep.RemoveWorkout)
    }

    @Test
    fun op33_ai_corrects_its_own_and_the_owners_exercises() {
        val s = step(AiOp.UpdateExercise("o", "ai-ex1", primary = listOf("QUADS", "GLUTES"), secondary = listOf("GLUTES", "ABS"), notes = " Back flat ")) as AiStep.ReplaceExercise
        assertEquals(aiExercise, s.before)
        assertEquals(setOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), s.after.exercise.primary)
        assertEquals(setOf(MuscleGroup.ABS), s.after.exercise.secondary)
        assertEquals("Back flat", s.after.notes)
        assertEquals("Wall sit pulse", s.after.exercise.name)
        assertEquals(now, s.after.updatedAtMillis)
        // The owner's own exercise, by its id; notes "" clears them.
        val mine = step(AiOp.UpdateExercise("o", "USER_mine0001", name = "Towel row (door)", notes = "")) as AiStep.ReplaceExercise
        assertEquals("Towel row (door)", mine.after.exercise.name)
        assertEquals(null, mine.after.notes)
    }

    @Test
    fun op34_catalog_family_archived_and_conflicting_exercise_changes_are_refused() {
        assertTrue(reason(AiOp.UpdateExercise("o", "PUSH_UP", name = "x")).contains("built-in"))
        assertTrue(reason(AiOp.UpdateExercise("o", "NOPE", name = "x")).contains("No exercise"))
        assertTrue(reason(AiOp.UpdateExercise("o", "USER_old00001", name = "x")).contains("archived"))
        val family = state.copy(sharedIn = setOf("USER_mine0001"))
        val d = AiOpPlanner.plan(AiOp.UpdateExercise("o", "USER_mine0001", name = "x"), family, now)
        assertTrue((d as AiDecision.Reject).reason.contains("family member"))
        assertTrue(reason(AiOp.UpdateExercise("o", "ai-ex1", name = "Push-up")).contains("exists already"))
        assertTrue(reason(AiOp.UpdateExercise("o", "ai-ex1", primary = emptyList())).contains("primary"))
        assertTrue(reason(AiOp.UpdateExercise("o", "ai-ex1", primary = listOf("WINGS"))).contains("Unknown muscle"))
        assertTrue(reason(AiOp.UpdateExercise("o", "ai-ex1", primary = listOf("QUADS"))).contains("Nothing to change"))
        // timed only changes while no workout uses the exercise.
        val used = state.copy(workouts = state.workouts + (3L to workout(3, "Uses mine").let { w -> w.copy(exercises = w.exercises.map { it.copy(exerciseId = "USER_mine0001") }) }))
        val t = AiOpPlanner.plan(AiOp.UpdateExercise("o", "USER_mine0001", timed = true), used, now)
        assertTrue((t as AiDecision.Reject).reason.contains("\"Uses mine\""))
        assertTrue(step(AiOp.UpdateExercise("o", "USER_mine0001", timed = true)) is AiStep.ReplaceExercise)
    }

    @Test
    fun op30_video_workout_keeps_link_title_minutes_and_source_lines() {
        val s = step(
            AiOp.CreateWorkout(
                "o", "ai-v1", "Glutes 20", "LOWER",
                listOf(row("PUSH_UP").copy(sourceLine = "  0:45 Push-ups x10 ")),
                videoUrl = "https://youtu.be/dQw4w9WgXcQ?t=30", videoTitle = " 20 min glutes ", videoMinutes = 22,
                sourceText = "0:45 Push-ups x10\n2:00 Squats",
            ),
        ) as AiStep.InsertWorkout
        val w = s.workout
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30", w.videoUrl)
        assertEquals("20 min glutes", w.videoTitle)
        assertEquals(22, w.durationMin)
        assertEquals(22, w.estimatedMinutes)
        assertEquals("0:45 Push-ups x10\n2:00 Squats", w.sourceText)
        assertEquals("0:45 Push-ups x10", w.exercises.single().sourceLine)
        // Other sites stay as they are.
        val other = step(AiOp.CreateWorkout("o", "ai-v2", "Yoga", rows = listOf(row("PUSH_UP")), videoUrl = "https://vimeo.com/123")) as AiStep.InsertWorkout
        assertEquals("https://vimeo.com/123", other.workout.videoUrl)
    }

    @Test
    fun op31_video_fields_are_checked() {
        fun r(url: String? = "https://youtu.be/dQw4w9WgXcQ", title: String? = null, min: Int? = null, src: String? = null) =
            reason(AiOp.CreateWorkout("o", "ai-v", "V", rows = listOf(row("PUSH_UP")), videoUrl = url, videoTitle = title, videoMinutes = min, sourceText = src))
        assertTrue(r(url = "http://youtu.be/dQw4w9WgXcQ").contains("https"))
        assertTrue(r(url = "javascript:alert(1)").contains("https"))
        assertTrue(r(url = "https://x.y/a b").contains("https"))
        assertTrue(r(url = null, title = "T").contains("need a videoUrl"))
        assertTrue(r(min = 0).contains("videoMinutes"))
        assertTrue(r(title = "x".repeat(201)).contains("videoTitle"))
        assertTrue(r(src = "x".repeat(5001)).contains("sourceText"))
    }

    @Test
    fun op32_update_adds_keeps_and_removes_the_video() {
        val added = step(AiOp.UpdateWorkout("o", "ai-w2", videoUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ", videoMinutes = 15)) as AiStep.ReplaceWorkout
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", added.after.videoUrl)
        assertEquals(15, added.after.durationMin)
        val withVideo = state.copy(workouts = state.workouts + (2L to added.after))
        val renamed = AiOpPlanner.plan(AiOp.UpdateWorkout("o", "ai-w2", name = "New"), withVideo, now) as AiDecision.Apply
        val kept = (renamed.step as AiStep.ReplaceWorkout).after
        assertEquals(added.after.videoUrl, kept.videoUrl)
        assertEquals(15, kept.durationMin)
        val removed = AiOpPlanner.plan(AiOp.UpdateWorkout("o", "ai-w2", videoUrl = ""), withVideo, now) as AiDecision.Apply
        val gone = (removed.step as AiStep.ReplaceWorkout).after
        assertEquals(null, gone.videoUrl)
        assertEquals(null, gone.durationMin)
        assertEquals(null, gone.sourceText)
    }

    // --- sessions --------------------------------------------------------------------------------

    @Test
    fun op11_schedule_a_run_with_catalog_defaults_and_load() {
        val s = step(AiOp.ScheduleSession("o", "ai-s1", AiSessionFields(day = "2026-12-03", start = "18:30", sport = "RUN_OUTDOOR", sessionType = "TEMPO_RUN", durationMin = 40, distanceKm = 8.0, pace = "5:00"))) as AiStep.InsertSession
        val p = s.session
        assertEquals(today + 2, p.day)
        assertEquals(18 * 60 + 30, p.startMinuteOfDay)
        assertEquals(SessionType.TEMPO_RUN, p.sessionType)
        assertEquals(Intensity.HIGH, p.intensity)
        assertEquals(8000.0, p.targetDistanceMeters!!, 0.01)
        assertEquals(300, p.targetPaceSecPerKm)
        assertNotNull(p.estimatedTrimp)
        assertEquals(PlannedStatus.PLANNED, p.status)
        assertEquals(false, p.locked)
        assertNull(p.sourceSuggestionId)
    }

    @Test
    fun op12_schedule_needs_day_and_sport_and_a_future_day() {
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(sport = "WALK"))).contains("day"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-12-03"))).contains("sport"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-11-30", sport = "WALK"))).contains("past"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "03.12.2026", sport = "WALK"))).contains("YYYY"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2028-12-03", sport = "WALK"))).contains("year"))
    }

    @Test
    fun op13_today_is_allowed() {
        assertTrue(step(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-12-01", sport = "MOBILITY"))) is AiStep.InsertSession)
    }

    @Test
    fun op14_session_type_must_fit_the_sport() {
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-12-03", sport = "CYCLING", sessionType = "LONG_RUN"))).contains("does not fit"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-12-03", sport = "UNKNOWN"))).contains("Unknown sport"))
        val swim = step(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-12-03", sport = "SWIM", durationMin = 30))) as AiStep.InsertSession
        assertEquals(SessionType.CROSS_TRAINING, swim.session.sessionType)
    }

    @Test
    fun op15_a_workout_sets_sport_type_and_duration() {
        val s = step(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = "2026-12-04", workout = "ai-w2"))) as AiStep.InsertSession
        assertEquals(SportType.STRENGTH, s.session.sportType)
        assertEquals(SessionType.STRENGTH_FULL, s.session.sessionType)
        assertEquals(2L, s.session.workoutId)
        assertEquals(workout(2, "x").estimatedMinutes, s.session.targetDurationMin)
        // The owner's workouts can be scheduled too — scheduling does not change them.
        assertTrue(step(AiOp.ScheduleSession("o", "ai-t", AiSessionFields(day = "2026-12-04", workout = "w1"))) is AiStep.InsertSession)
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-u", AiSessionFields(day = "2026-12-04", sport = "RUN_OUTDOOR", workout = "w1"))).contains("strength or mobility"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-v", AiSessionFields(day = "2026-12-04", workout = "w77"))).contains("No workout"))
    }

    @Test
    fun op16_values_out_of_range_are_refused() {
        val d = "2026-12-03"
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = d, sport = "WALK", durationMin = 0))).contains("Duration"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = d, sport = "WALK", distanceKm = 400.0))).contains("Distance"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = d, sport = "RUN_OUTDOOR", pace = "1:30"))).contains("Pace"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = d, sport = "RUN_OUTDOOR", pace = "fast"))).contains("m:ss"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = d, sport = "WALK", start = "25:00"))).contains("HH:MM"))
        assertTrue(reason(AiOp.ScheduleSession("o", "ai-s", AiSessionFields(day = d, sport = "WALK", intensity = "EXTREME"))).contains("intensity"))
    }

    @Test
    fun op17_update_an_unlocked_session_keeps_untouched_fields() {
        val s = step(AiOp.UpdateSession("o", "s10", AiSessionFields(durationMin = 60, rationale = "Build aerobic base"))) as AiStep.ReplaceSession
        assertEquals(state.sessions[10L], s.before)
        assertEquals(60, s.after.targetDurationMin)
        assertEquals(SessionType.EASY_RUN, s.after.sessionType)
        assertEquals(Intensity.LOW, s.after.intensity)
        assertEquals("easy", s.after.description)
        assertEquals("Build aerobic base", s.after.rationale)
        assertEquals(10L, s.after.id)
        assertTrue(s.after.estimatedTrimp!! > s.before.estimatedTrimp!!)
    }

    @Test
    fun op18_the_owners_unlocked_sessions_may_be_replaced() {
        // "add + replace unlocked": session 10 was not created by the AI.
        assertTrue(step(AiOp.UpdateSession("o", "s10", AiSessionFields(day = "2026-12-05"))) is AiStep.ReplaceSession)
        assertTrue(step(AiOp.DeleteSession("o", "s10")) is AiStep.RemoveSession)
    }

    @Test
    fun op19_locked_done_and_past_sessions_are_fixed() {
        assertTrue(reason(AiOp.UpdateSession("o", "s11", AiSessionFields(durationMin = 30))).contains("locked"))
        assertTrue(reason(AiOp.DeleteSession("o", "s11")).contains("locked"))
        assertTrue(reason(AiOp.DeleteSession("o", "s12")).contains("done"))
        assertTrue(reason(AiOp.UpdateSession("o", "s13", AiSessionFields(day = "2026-12-05"))).contains("past"))
        assertTrue(reason(AiOp.DeleteSession("o", "s99")).contains("No planned session"))
    }

    @Test
    fun op20_a_session_locked_after_the_ai_planned_it_is_rejected() {
        val lockedLater = state.copy(sessions = state.sessions + (14L to session(14, locked = true, workoutId = 2)))
        val d = AiOpPlanner.plan(AiOp.UpdateSession("o", "ai-s14", AiSessionFields(durationMin = 20)), lockedLater, now)
        assertTrue(d is AiDecision.Reject && d.reason.contains("locked"))
    }

    @Test
    fun op21_moving_a_session_into_the_past_is_refused() {
        assertTrue(reason(AiOp.UpdateSession("o", "s10", AiSessionFields(day = "2026-11-20"))).contains("past"))
    }

    @Test
    fun op22_changing_the_sport_resets_type_and_intensity_to_fit() {
        val s = step(AiOp.UpdateSession("o", "s10", AiSessionFields(sport = "CYCLING"))) as AiStep.ReplaceSession
        assertEquals(SportType.CYCLING, s.after.sportType)
        assertTrue(s.after.sessionType in AiOpPlanner.typesFor(SportType.CYCLING))
    }

    @Test
    fun op23_empty_strings_clear_start_pace_and_workout() {
        val base = state.copy(sessions = state.sessions + (10L to session(10).copy(startMinuteOfDay = 600, targetPaceSecPerKm = 330)))
        val d = AiOpPlanner.plan(AiOp.UpdateSession("o", "s10", AiSessionFields(start = "", pace = "")), base, now) as AiDecision.Apply
        val after = (d.step as AiStep.ReplaceSession).after
        assertNull(after.startMinuteOfDay)
        assertNull(after.targetPaceSecPerKm)
    }

    @Test
    fun op24_an_ai_ref_of_the_wrong_kind_is_not_found() {
        assertTrue(reason(AiOp.DeleteSession("o", "ai-w2")).contains("No planned session"))
        assertTrue(reason(AiOp.UpdateWorkout("o", "ai-s14", name = "x")).contains("No workout"))
    }

    @Test
    fun op25_parsers() {
        assertEquals(0, AiOpPlanner.parseTime("0:00"))
        assertEquals(23 * 60 + 59, AiOpPlanner.parseTime("23:59"))
        assertNull(AiOpPlanner.parseTime("24:00"))
        assertEquals(4 * 60 + 5, AiOpPlanner.parsePace("4:05"))
        assertNull(AiOpPlanner.parsePace("4:60"))
        assertEquals(today, AiOpPlanner.parseDay("2026-12-01"))
        assertNull(AiOpPlanner.parseDay("2026-13-01"))
    }

    // --- wire format ----------------------------------------------------------------------------

    @Test
    fun op26_ops_decode_from_relay_json() {
        val json = Json.parseToJsonElement(
            """{"type":"schedule_session","opId":"op-1","aiId":"ai-s7","session":{"day":"2026-12-03","sport":"RUN_OUTDOOR","durationMin":45},"extra":true}""",
        )
        val op = AiOp.decode(json) as AiOp.ScheduleSession
        assertEquals("op-1", op.opId)
        assertEquals(45, op.session.durationMin)
        assertNull(AiOp.decode(Json.parseToJsonElement("""{"type":"launch_rockets","opId":"x"}""")))
        assertNull(AiOp.decode(Json.parseToJsonElement("""{"type":"delete_session"}""")))
    }

    @Test
    fun op27_every_op_round_trips() {
        val ops = listOf(
            AiOp.CreateExercise("1", "ai-a", "A", listOf("CHEST")),
            AiOp.SetEquipment("2", listOf("BAND")),
            AiOp.UpdateExercise("11", "ai-a", primary = listOf("QUADS"), notes = ""),
            AiOp.CreateWorkout("3", "ai-b", "B", rows = listOf(row("PUSH_UP"))),
            AiOp.UpdateWorkout("4", "ai-b", name = "C"),
            AiOp.CreateWorkout("9", "ai-v", "V", rows = listOf(row("PUSH_UP").copy(sourceLine = "0:10 x")), videoUrl = "https://youtu.be/dQw4w9WgXcQ", videoTitle = "T", videoMinutes = 12, sourceText = "s"),
            AiOp.UpdateWorkout("10", "ai-v", videoUrl = ""),
            AiOp.DeleteWorkout("5", "ai-b"),
            AiOp.ScheduleSession("6", "ai-c", AiSessionFields(day = "2026-12-03", sport = "WALK")),
            AiOp.UpdateSession("7", "s1", AiSessionFields(durationMin = 30)),
            AiOp.DeleteSession("8", "s1"),
        )
        for (op in ops) {
            val element = AiOp.JSON.encodeToJsonElement(AiOp.serializer(), op)
            assertEquals(op, AiOp.decode(element))
        }
    }
}

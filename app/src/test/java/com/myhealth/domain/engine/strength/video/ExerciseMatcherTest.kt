package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.UserExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.3: pasted names → library exercises. */
class ExerciseMatcherTest {

    private val matcher = ExerciseMatcher(ExerciseLibrary.CATALOG_ONLY)

    private fun matched(name: String): String? =
        matcher.match(name).takeIf { it.status == MatchStatus.MATCHED }?.best?.id

    @Test
    fun exact_names_match() {
        assertEquals("PUSH_UP", matched("Push-up"))
        assertEquals("GLUTE_BRIDGE", matched("Glute bridge"))
        assertEquals("MOB_CAT_COW", matched("Cat-cow"))
    }

    @Test
    fun spacing_hyphens_and_plurals_do_not_matter() {
        assertEquals("PUSH_UP", matched("pushups"))
        assertEquals("PUSH_UP", matched("Push Ups"))
        assertEquals("GLUTE_BRIDGE", matched("Glute Bridges"))
        assertEquals("WALKING_LUNGE", matched("walking lunges"))
        assertEquals("STEP_UP", matched("Step ups"))
    }

    @Test
    fun german_names_match_through_synonyms() {
        assertEquals("PUSH_UP", matched("Liegestütze"))
        assertEquals("PLANK", matched("Unterarmstütz"))
        assertEquals("GLUTE_BRIDGE", matched("Beckenheben"))
        assertEquals("CALF_RAISE", matched("Wadenheben"))
        assertEquals("CONVENTIONAL_DEADLIFT", matched("Kreuzheben"))
        assertEquals("MOB_CHILDS_POSE", matched("Kindhaltung"))
    }

    @Test
    fun english_alternative_names_match() {
        assertEquals("PUSH_UP", matched("Press-ups"))
        assertEquals("OVERHEAD_PRESS", matched("Shoulder press"))
        assertEquals("ROMANIAN_DEADLIFT", matched("RDL"))
        assertEquals("TRICEPS_DIP", matched("Chair dips"))
        assertEquals("MOB_DOWNWARD_DOG", matched("Down dog"))
    }

    @Test
    fun typos_still_match() {
        assertEquals("GLUTE_BRIDGE", matched("Glute brigde"))
        assertEquals("RUSSIAN_TWIST", matched("Rusian twist"))
        assertEquals("KETTLEBELL_SWING", matched("Kettlebel swing"))
    }

    @Test
    fun filler_words_are_ignored() {
        assertEquals("REVERSE_LUNGE", matched("Alternating reverse lunges"))
        assertEquals("BICEPS_CURL", matched("Slow bicep curls"))
    }

    @Test
    fun a_variation_is_a_check_with_the_base_move_first() {
        val r = matcher.match("Glute bridge pulses")
        assertEquals(MatchStatus.CHECK, r.status)
        assertEquals("GLUTE_BRIDGE", r.best?.id)
    }

    @Test
    fun a_vague_name_is_a_check_with_up_to_three_candidates() {
        val r = matcher.match("Squat")
        assertEquals(MatchStatus.CHECK, r.status)
        assertTrue(r.candidates.size in 2..3)
        assertTrue(r.candidates.all { it.exercise.name.contains("squat", ignoreCase = true) })
    }

    @Test
    fun unknown_moves_are_not_found() {
        val r = matcher.match("Jumping jacks")
        assertEquals(MatchStatus.NOT_FOUND, r.status)
        assertNull(r.best)
        assertEquals(MatchStatus.NOT_FOUND, matcher.match("Burpees").status)
        assertEquals(MatchStatus.NOT_FOUND, matcher.match("").status)
    }

    @Test
    fun equipment_word_prefers_that_equipment() {
        val r = matcher.match("Dumbbell row")
        assertEquals("DUMBBELL_ROW", r.best?.id)
        val b = matcher.match("Barbell row")
        assertEquals("BARBELL_ROW", b.best?.id)
    }

    @Test
    fun learned_name_wins_over_everything() {
        val m = ExerciseMatcher(ExerciseLibrary.CATALOG_ONLY, mapOf(ExerciseMatcher.key("Booty bridges") to "HIP_THRUST"))
        val r = m.match("booty bridge")
        assertEquals(MatchStatus.MATCHED, r.status)
        assertEquals("HIP_THRUST", r.best?.id)
        assertTrue(r.fromAlias)
    }

    @Test
    fun learned_name_pointing_at_a_deleted_exercise_is_ignored() {
        val m = ExerciseMatcher(ExerciseLibrary.CATALOG_ONLY, mapOf(ExerciseMatcher.key("Booty bridge") to "USER_GONE23456789"))
        assertEquals("GLUTE_BRIDGE", m.match("Booty bridge").best?.id)
    }

    @Test
    fun own_exercises_take_part() {
        val own = userExercise("USER_FIREHYDRANT1", "Fire hydrant")
        val m = ExerciseMatcher(ExerciseLibrary(listOf(own)))
        assertEquals("USER_FIREHYDRANT1", m.match("Fire hydrants").best?.id)
        assertEquals(MatchStatus.MATCHED, m.match("fire-hydrant").status)
    }

    @Test
    fun archived_own_exercises_are_not_offered_but_resolve_through_aliases() {
        val own = userExercise("USER_ARCHIVED2345", "Fire hydrant", archived = true)
        val library = ExerciseLibrary(listOf(own))
        assertEquals(MatchStatus.NOT_FOUND, ExerciseMatcher(library).match("Fire hydrant").status)
        val withAlias = ExerciseMatcher(library, mapOf(ExerciseMatcher.key("Fire hydrant") to own.exercise.id))
        assertEquals(own.exercise.id, withAlias.match("Fire hydrant").best?.id)
    }

    @Test
    fun key_folds_case_accents_punctuation_fillers_and_plurals() {
        assertEquals("push up", ExerciseMatcher.key("Push-Ups!"))
        assertEquals("liegestutze", ExerciseMatcher.key("Liegestütze"))
        assertEquals("reverse lunge", ExerciseMatcher.key("Alternating Reverse Lunges"))
        assertEquals("farmer carry", ExerciseMatcher.key("Farmer’s carry"))
        assertEquals(ExerciseMatcher.key("crunches"), ExerciseMatcher.key("Crunch"))
        assertEquals("press", ExerciseMatcher.singular("presses"))
    }

    @Test
    fun matched_status_is_never_given_to_two_tied_exercises() {
        ExerciseCatalog.ALL.forEach { e ->
            val r = matcher.match(e.name)
            assertEquals(e.name, MatchStatus.MATCHED, r.status)
            assertEquals(e.name, e.id, r.best?.id)
        }
    }

    @Test
    fun different_moves_are_not_merged() {
        assertNotEquals(matched("Pull-up"), matched("Chin-up"))
        assertEquals("SIDE_PLANK", matched("Side plank"))
        assertEquals("PLANK", matched("Plank"))
    }

    private fun userExercise(id: String, name: String, archived: Boolean = false) = UserExercise(
        exercise = Exercise(
            id = id, name = name, primary = setOf(MuscleGroup.GLUTES), equipment = Equipment.BODYWEIGHT,
            pattern = MovementPattern.OTHER, cue = "",
        ),
        notes = null, archived = archived, createdAtMillis = 1L, updatedAtMillis = 1L,
    )
}

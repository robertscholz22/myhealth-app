package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.UserExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.2: the own-exercise editor's draft rules. */
class ExerciseEditDraftTest {

    @Test
    fun figure_tap_cycles_primary_secondary_off() {
        val m = MuscleGroup.GLUTES
        val a = ExerciseEditDraft().cycleMuscle(m)
        assertEquals(setOf(m), a.primary)
        val b = a.cycleMuscle(m)
        assertEquals(emptySet<MuscleGroup>(), b.primary)
        assertEquals(setOf(m), b.secondary)
        val c = b.cycleMuscle(m)
        assertTrue(c.primary.isEmpty() && c.secondary.isEmpty())
    }

    @Test
    fun chip_rows_keep_primary_and_secondary_disjoint() {
        val m = MuscleGroup.QUADS
        val d = ExerciseEditDraft().togglePrimary(m).toggleSecondary(m)
        assertEquals(emptySet<MuscleGroup>(), d.primary)
        assertEquals(setOf(m), d.secondary)
        val e = d.togglePrimary(m)
        assertEquals(setOf(m), e.primary)
        assertEquals(emptySet<MuscleGroup>(), e.secondary)
    }

    @Test
    fun save_needs_a_name_and_a_primary_muscle() {
        assertFalse(ExerciseEditDraft(name = "  ").canSave)
        assertFalse(ExerciseEditDraft(name = "Band walk").canSave)
        assertTrue(ExerciseEditDraft(name = "Band walk", primary = setOf(MuscleGroup.GLUTES)).canSave)
    }

    @Test
    fun new_exercise_draws_an_id_and_stamps_both_times() {
        val ue = ExerciseEditDraft(name = " Band walk ", primary = setOf(MuscleGroup.GLUTES))
            .toUserExercise({ "USER_ABCDEFGHJKLM" }, 1_000L)
        assertEquals("USER_ABCDEFGHJKLM", ue.exercise.id)
        assertEquals("Band walk", ue.exercise.name)
        assertEquals(MovementPattern.OTHER, ue.exercise.pattern)
        assertEquals(Equipment.BODYWEIGHT, ue.exercise.equipment)
        assertEquals(1_000L, ue.createdAtMillis)
        assertEquals(1_000L, ue.updatedAtMillis)
        assertNull(ue.notes)
    }

    @Test
    fun mobility_is_timed_with_the_mobility_pattern() {
        val ue = ExerciseEditDraft(
            name = "Hip opener",
            primary = setOf(MuscleGroup.ADDUCTORS),
            mobility = true,
            pattern = MovementPattern.SQUAT,
        ).toUserExercise({ "USER_X" }, 5L)
        assertEquals(MovementPattern.MOBILITY, ue.exercise.pattern)
        assertTrue(ue.exercise.isTimed)
        assertTrue(ue.exercise.isMobility)
    }

    @Test
    fun editing_keeps_id_and_creation_time_and_round_trips() {
        val original = UserExercise(
            exercise = Exercise(
                id = "USER_KEEPME234567",
                name = "Donkey kick",
                primary = setOf(MuscleGroup.GLUTES),
                secondary = setOf(MuscleGroup.HAMSTRINGS),
                equipment = Equipment.BODYWEIGHT,
                pattern = MovementPattern.HINGE,
                unilateral = true,
                isTimed = false,
                cue = "Squeeze at the top",
            ),
            notes = "Squeeze at the top",
            archived = false,
            createdAtMillis = 10L,
            updatedAtMillis = 10L,
        )
        val saved = ExerciseEditDraft.of(original).copy(name = "Donkey kicks")
            .toUserExercise({ error("no new id for an edit") }, 99L)
        assertEquals("USER_KEEPME234567", saved.exercise.id)
        assertEquals(10L, saved.createdAtMillis)
        assertEquals(99L, saved.updatedAtMillis)
        assertEquals(original.exercise.copy(name = "Donkey kicks"), saved.exercise)
    }

    @Test
    fun duplicate_ignores_case_punctuation_and_plural() {
        val library = ExerciseLibrary.CATALOG_ONLY
        val pushUp = library.byId("PUSH_UP")!!
        assertEquals(pushUp.id, ExerciseEditDraft(name = "${pushUp.name.uppercase()}s!").duplicateIn(library)?.id)
        assertNull(ExerciseEditDraft(name = "Banded clamshell 3000").duplicateIn(library))
        assertNull(ExerciseEditDraft(name = "").duplicateIn(library))
    }

    @Test
    fun duplicate_check_skips_the_exercise_being_edited() {
        val own = UserExercise(
            exercise = Exercise(
                id = "USER_SELF23456789", name = "Band walk", primary = setOf(MuscleGroup.GLUTES),
                secondary = emptySet(), equipment = Equipment.BAND, pattern = MovementPattern.OTHER,
                unilateral = false, isTimed = false, cue = "",
            ),
            notes = null, archived = false, createdAtMillis = 1L, updatedAtMillis = 1L,
        )
        val library = ExerciseLibrary(listOf(own))
        assertNull(ExerciseEditDraft.of(own).duplicateIn(library))
        assertEquals(own.exercise.id, ExerciseEditDraft(name = "band walks").duplicateIn(library)?.id)
    }
}

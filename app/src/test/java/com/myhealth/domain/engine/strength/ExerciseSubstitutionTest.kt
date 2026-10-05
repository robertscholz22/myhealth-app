package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Equipment
import org.junit.Test

/**
 * "My equipment" (PLAN §P16, `eq01`…`eq05`): what a built-in workout turns into when the owner
 * only has part of a gym.
 */
class ExerciseSubstitutionTest {

    private val home = setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL)

    @Test
    fun eq01_substitute_same_pattern_and_primaries() {
        val bench = exercise("BARBELL_BENCH_PRESS")

        val replacement = ExerciseSubstitution.substitute(bench, home)

        // Push-up: same pattern (HORIZONTAL_PUSH) and the same primary set {CHEST}. The incline
        // dumbbell press is available too, but its primaries are {CHEST, SHOULDERS_FRONT}.
        assertThat(replacement?.id).isEqualTo("PUSH_UP")
        // An exercise whose own equipment is available is never substituted.
        val curl = exercise("BICEPS_CURL")
        assertThat(ExerciseSubstitution.substitute(curl, home)?.id).isEqualTo("BICEPS_CURL")
    }

    @Test
    fun eq02_fallback_shared_primary() {
        val incline = exercise("INCLINE_DUMBBELL_PRESS")

        // Bodyweight only: nothing shares {CHEST, SHOULDERS_FRONT} exactly, so the fallback takes
        // the first HORIZONTAL_PUSH entry that shares at least one primary — the push-up (CHEST).
        val replacement = ExerciseSubstitution.substitute(incline, setOf(Equipment.BODYWEIGHT))

        assertThat(replacement?.id).isEqualTo("PUSH_UP")
    }

    @Test
    fun eq03_dropped_when_nothing_fits() {
        val squat = exercise("BARBELL_BACK_SQUAT")

        // The catalog has no SQUAT entry that uses a band, so the row is dropped, not kept.
        assertThat(ExerciseSubstitution.substitute(squat, setOf(Equipment.BAND))).isNull()
        // Nor a VERTICAL_PUSH one that shares a primary with the overhead press.
        assertThat(ExerciseSubstitution.substitute(exercise("OVERHEAD_PRESS"), home)).isNull()
    }

    @Test
    fun eq04_null_equipment_means_everything() {
        val squat = exercise("BARBELL_BACK_SQUAT")

        assertThat(ExerciseSubstitution.substitute(squat, available = null)?.id)
            .isEqualTo("BARBELL_BACK_SQUAT")
        // A template materialises byte-identically — the P16 invariant for an unrestricted profile.
        assertThat(ExerciseSubstitution.materialise(StrengthTemplates.UPPER_A, null))
            .isEqualTo(StrengthTemplates.UPPER_A)
        assertThat(EquipmentSetCodec.decode(null)).isNull()
        assertThat(EquipmentSetCodec.decode("[]")).isNull()
        assertThat(EquipmentSetCodec.decode("not json")).isNull()
        assertThat(EquipmentSetCodec.decode("""["BODYWEIGHT","DUMBBELL","NOT_A_THING"]""")).isEqualTo(home)
        assertThat(EquipmentSetCodec.encode(home)).isEqualTo("""["BODYWEIGHT","DUMBBELL"]""")
        assertThat(EquipmentSetCodec.encode(Equipment.entries.toSet())).isNull()
    }

    @Test
    fun eq05_template_materialised_against_home_set() {
        val materialised = ExerciseSubstitution.materialise(StrengthTemplates.UPPER_A, home)

        // Bench → push-up, barbell row → dumbbell row, lat pulldown → pull-up, the curl stays;
        // the overhead press and the cable pushdown have no home equivalent and are dropped, and
        // the workout keeps the rest rather than failing as a whole.
        assertThat(materialised.exercises.map { it.exerciseId })
            .containsExactly("PUSH_UP", "DUMBBELL_ROW", "PULL_UP", "BICEPS_CURL")
            .inOrder()
        // The surviving rows are renumbered, so `uq_swe_order` still holds.
        assertThat(materialised.exercises.map { it.orderIndex }).containsExactly(0, 1, 2, 3).inOrder()
        // A bodyweight substitute is flagged as one; the prescription comes from the engine.
        assertThat(materialised.exercises.first { it.exerciseId == "PUSH_UP" }.isBodyweight).isTrue()
        assertThat(materialised.exercises.first { it.exerciseId == "DUMBBELL_ROW" }.isBodyweight).isFalse()
        // Sets and reps of the template are preserved.
        assertThat(materialised.exercises.map { it.sets }.distinct()).containsExactly(3)
        assertThat(materialised.exercises.first().reps).isEqualTo(10)
        assertThat(materialised.name).isEqualTo(StrengthTemplates.UPPER_A.name)
    }

    private fun exercise(id: String) = checkNotNull(ExerciseCatalog.byId(id)) { "no $id in the catalog" }
}

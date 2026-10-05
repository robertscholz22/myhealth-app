package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.engine.suggest.MuscleContext
import com.myhealth.domain.engine.suggest.StrengthRules
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import org.junit.Test

/**
 * P17.1 (PLAN §P17): the mobility catalog, the routine chooser and the two invariants that keep
 * mobility out of the strength machinery — it deposits no muscle load, and no substitution ever
 * crosses between the two halves of the catalog.
 */
class MobilityCatalogTest {

    @Test
    fun mob01_catalog_has_at_least_30_mobility_exercises_all_timed() {
        val mobility = ExerciseCatalog.mobility
        assertThat(mobility.size).isAtLeast(30)

        mobility.forEach { exercise ->
            assertWithMessage(exercise.id).that(exercise.isTimed).isTrue()
            assertWithMessage(exercise.id).that(exercise.pattern).isEqualTo(MovementPattern.MOBILITY)
            assertWithMessage(exercise.id).that(exercise.isMobility).isTrue()
            assertWithMessage(exercise.id).that(exercise.primary).isNotEmpty()
            assertWithMessage(exercise.id).that(exercise.cue).isNotEmpty()
            assertWithMessage(exercise.id).that(exercise.equipment).isIn(
                listOf(Equipment.BODYWEIGHT, Equipment.BAND, Equipment.FOAM_ROLLER),
            )
        }

        // The owner's own list is all there, under the ids a stored row will keep for ever.
        assertThat(mobility.map { it.id }).containsAtLeast(
            "MOB_COUCH_STRETCH", "MOB_PIGEON", "MOB_HIP_SWITCH_90_90",
            "MOB_STANDING_HAMSTRING_STRETCH", "MOB_WALL_CALF_STRETCH", "MOB_ANKLE_ROCKS",
            "MOB_DEEP_SQUAT_HOLD", "MOB_ADDUCTOR_ROCK_BACK", "MOB_FIGURE_FOUR_GLUTE",
            "MOB_STANDING_QUAD_STRETCH", "MOB_WORLDS_GREATEST_STRETCH", "MOB_LEG_SWINGS",
            "MOB_CAT_COW", "MOB_THREAD_THE_NEEDLE", "MOB_THORACIC_ROTATION", "MOB_CHILDS_POSE",
            "MOB_DOWNWARD_DOG", "MOB_COBRA", "MOB_SHOULDER_CARS", "MOB_WALL_SLIDES",
            "MOB_DOORWAY_PEC_STRETCH", "MOB_BAND_PULL_APART_SLOW", "MOB_DOORWAY_LAT_STRETCH",
            "MOB_WRIST_CIRCLES", "MOB_NECK_ROTATIONS", "MOB_FOAM_ROLL_QUADS",
            "MOB_FOAM_ROLL_HAMSTRINGS", "MOB_FOAM_ROLL_CALVES", "MOB_FOAM_ROLL_THORACIC",
            "MOB_FOAM_ROLL_LATS", "MOB_FOAM_ROLL_GLUTES",
        )
        // Six foam-roll drills, and the foam roller is used by nothing else.
        assertThat(ExerciseCatalog.filter(equipment = Equipment.FOAM_ROLLER).map { it.id })
            .hasSize(6)
        assertThat(ExerciseCatalog.filter(equipment = Equipment.FOAM_ROLLER).all { it.isMobility })
            .isTrue()

        // The two halves partition the catalog, and the kind filter is what the chip row reads.
        assertThat(ExerciseCatalog.strength + ExerciseCatalog.mobility)
            .containsExactlyElementsIn(ExerciseCatalog.ALL)
        assertThat(ExerciseCatalog.strength.none { it.isMobility }).isTrue()
        assertThat(ExerciseCatalog.filter(kind = ExerciseKind.MOBILITY)).isEqualTo(mobility)
        assertThat(ExerciseCatalog.filter(kind = ExerciseKind.STRENGTH))
            .isEqualTo(ExerciseCatalog.strength)
        assertThat(ExerciseCatalog.filter(kind = null)).isEqualTo(ExerciseCatalog.ALL)
        assertThat(ExerciseCatalog.search("squat", kind = ExerciseKind.MOBILITY).map { it.id })
            .containsExactly("MOB_DEEP_SQUAT_HOLD")
        assertThat(ExerciseCatalog.search("", kind = ExerciseKind.MOBILITY)).isEqualTo(mobility)
    }

    @Test
    fun mob02_every_muscle_group_is_a_mobility_primary() {
        val covered = ExerciseCatalog.mobility.flatMap { it.primary }.toSet()
        assertThat(covered).containsExactlyElementsIn(MuscleGroup.entries)
        MuscleGroup.entries.forEach { group ->
            assertWithMessage(group.name)
                .that(ExerciseCatalog.byPrimary(group).any { it.isMobility })
                .isTrue()
        }
    }

    @Test
    fun mob05_template_for_lower_loaded_is_lower() {
        // A 220 AU run yesterday: the legs are fatigued, the upper body untouched.
        val state = MuscleLoadEngine.compute(
            MuscleLoadInput(
                today = 100L,
                ctl = 40.0,
                sessions = listOf(MuscleSession(day = 99L, sportGroup = SportGroup.RUN, trimp = 220.0)),
            ),
        )
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.FATIGUED)
        assertThat(state.upperBody).isEqualTo(MuscleLoadBand.FRESH)

        assertThat(StrengthRules.mobilityTemplateFor(state)).isEqualTo("MOBILITY_LOWER_A")
        assertThat(StrengthTemplates.byId(StrengthRules.mobilityTemplateFor(state)))
            .isEqualTo(StrengthTemplates.MOBILITY_LOWER_A)
    }

    @Test
    fun mob06_template_for_upper_loaded_is_upper() {
        // A hard swim leaves lats and shoulders loaded and the legs fresh.
        val state = MuscleLoadEngine.compute(
            MuscleLoadInput(
                today = 100L,
                // ctl 80 → ref 28: the swim's 12 AU on the glutes is well inside FRESH, its
                // 56 AU on the lats well past FATIGUED.
                ctl = 80.0,
                sessions = listOf(MuscleSession(day = 100L, sportGroup = SportGroup.SWIM, trimp = 200.0)),
            ),
        )
        assertThat(state.upperBody).isEqualTo(MuscleLoadBand.FATIGUED)
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.FRESH)

        assertThat(StrengthRules.mobilityTemplateFor(state)).isEqualTo("MOBILITY_UPPER_A")
    }

    @Test
    fun mob07_default_is_full() {
        // No muscle-load state at all.
        assertThat(StrengthRules.mobilityTemplateFor(null)).isEqualTo("MOBILITY_FULL_A")

        // Everything fresh: neither half stands out.
        val fresh = MuscleLoadEngine.compute(MuscleLoadInput(today = 100L, ctl = 40.0))
        assertThat(StrengthRules.mobilityTemplateFor(fresh)).isEqualTo("MOBILITY_FULL_A")

        // Both halves loaded: still the full-body routine, not one of the two halves.
        val both = MuscleLoadEngine.compute(
            MuscleLoadInput(
                today = 100L,
                ctl = 40.0,
                sessions = listOf(
                    MuscleSession(day = 100L, sportGroup = SportGroup.RUN, trimp = 220.0),
                    MuscleSession(day = 100L, sportGroup = SportGroup.SWIM, trimp = 220.0),
                ),
            ),
        )
        assertThat(StrengthRules.mobilityTemplateFor(both)).isEqualTo("MOBILITY_FULL_A")
        assertThat(StrengthTemplates.byId("MOBILITY_FULL_A")).isEqualTo(StrengthTemplates.MOBILITY_FULL_A)

        // The chooser is inert without a state: no session names a routine when the layer is off.
        assertThat(StrengthRules.templateIdFor(SessionType.MOBILITY, MuscleContext.NONE)).isNull()
        val ctx = MuscleContext(todayDay = 100L, state = fresh)
        assertThat(StrengthRules.templateIdFor(SessionType.MOBILITY, ctx)).isEqualTo("MOBILITY_FULL_A")
    }

    @Test
    fun mob08_mobility_workout_adds_no_muscle_load() {
        val session = MuscleSession(
            day = 100L,
            sportGroup = SportGroup.STRENGTH,
            sessionType = SessionType.STRENGTH_FULL,
            trimp = 120.0,
            workout = StrengthTemplates.MOBILITY_LOWER_A,
        )
        assertThat(MuscleLoadEngine.sharesOf(session)).isEmpty()
        assertThat(MuscleLoadEngine.depositOf(session)).isEmpty()

        val state = MuscleLoadEngine.compute(
            MuscleLoadInput(today = 100L, ctl = 40.0, sessions = listOf(session)),
        )
        assertThat(state.byGroup.values.all { it == 0.0 }).isTrue()
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.FRESH)
        assertThat(state.upperBody).isEqualTo(MuscleLoadBand.FRESH)

        // …and that is true of a user's own routine too, as long as every row is a mobility drill.
        val custom = StrengthTemplates.MOBILITY_FULL_A.copy(
            kind = StrengthWorkoutKind.CUSTOM,
            templateId = null,
            isBuiltIn = false,
        )
        assertThat(MuscleDistribution.isMobilityOnly(custom)).isTrue()
        assertThat(MuscleLoadEngine.sharesOf(session.copy(workout = custom))).isEmpty()

        // A strength workout is unaffected: the P14 behaviour is exactly what it was.
        val lifting = session.copy(workout = StrengthTemplates.LOWER_A)
        assertThat(MuscleDistribution.isMobilityOnly(StrengthTemplates.LOWER_A)).isFalse()
        assertThat(MuscleLoadEngine.sharesOf(lifting)).isNotEmpty()
    }

    @Test
    fun mob09_substitution_never_crosses_kinds() {
        val everything = Equipment.entries.toSet()
        val bodyweightOnly = setOf(Equipment.BODYWEIGHT)
        val noRoller = everything - Equipment.FOAM_ROLLER

        listOf(everything, bodyweightOnly, noRoller, setOf(Equipment.BAND)).forEach { available ->
            ExerciseCatalog.ALL.forEach { exercise ->
                val replacement = ExerciseSubstitution.substitute(exercise, available) ?: return@forEach
                assertWithMessage("${exercise.id} -> ${replacement.id}")
                    .that(replacement.isMobility)
                    .isEqualTo(exercise.isMobility)
            }
        }

        // A foam-roll drill on a profile without a roller finds a bodyweight mobility drill or
        // nothing — never a lift, and never a counted exercise.
        val rollQuads = requireNotNull(ExerciseCatalog.byId("MOB_FOAM_ROLL_QUADS"))
        val swapped = ExerciseSubstitution.substitute(rollQuads, bodyweightOnly)
        assertThat(swapped?.isMobility).isTrue()
        assertThat(swapped?.isTimed).isTrue()
        assertThat(swapped?.primary).contains(MuscleGroup.QUADS)

        // And the band pull-apart of the mobility half is never offered for a strength row.
        val pullApart = requireNotNull(ExerciseCatalog.byId("MOB_BAND_PULL_APART_SLOW"))
        assertThat(pullApart.isMobility).isTrue()
        ExerciseCatalog.strength.forEach { lift ->
            assertWithMessage(lift.id)
                .that(ExerciseSubstitution.substitute(lift, setOf(Equipment.BAND))?.id)
                .isNotEqualTo(pullApart.id)
        }

        // A mobility template materialised against a roller-free profile keeps only mobility rows.
        val materialised = ExerciseSubstitution.materialise(
            StrengthTemplates.MOBILITY_LOWER_A,
            bodyweightOnly,
        )
        assertThat(materialised.exercises).isNotEmpty()
        materialised.exercises.forEach { row ->
            assertThat(requireNotNull(ExerciseCatalog.byId(row.exerciseId)).isMobility).isTrue()
        }
    }
}

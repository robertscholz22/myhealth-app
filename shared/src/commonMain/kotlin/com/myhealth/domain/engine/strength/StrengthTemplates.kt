package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind

/** The rest a template prescribes for a compound set; the same default the estimate assumes. */
private const val DEFAULT_REST = StrengthWorkout.DEFAULT_REST_SEC

/** The rest for an isolation or a hold — short enough that the core day stays a core day. */
private const val SHORT_REST = 60

/**
 * P17: the rest between two mobility holds. A mobility routine is not a set-and-rest workout — the
 * 15 s is the time it takes to change sides or fetch the roller, and it is what makes the three
 * `MOBILITY_*` templates land at the ≈ 20 min (18–22) §P17 asks for without touching
 * [StrengthWorkout.DEFAULT_REST_SEC], which every strength template still uses.
 */
private const val MOBILITY_REST = 15

/**
 * The nine built-in workouts (PLAN §3.12.3 + §P17): two upper days, two lower days, one full-body
 * day, one core day (each 5–7 exercises deep) and, since P17, three mobility routines of 7–8 timed
 * holds at ≈ 20 minutes.
 *
 * They are **code, not a migration**: `StrengthWorkoutSeeder` materialises them into
 * `strength_workout` the first time they are needed, keyed on [StrengthWorkout.templateId], so a
 * template can still be corrected in a later release (the seeder leaves an already-seeded row
 * alone — a user's edits to their copy are theirs).
 *
 * Every id is checked against [ExerciseCatalog] at class-initialisation time, and a held exercise
 * may only be prescribed with [hold] (seconds) and a counted one only with [reps] — which is what
 * `ex08` asserts from the outside.
 */
object StrengthTemplates {

    /** The pinned reference workout (`sw04`): 6 × 3 × (10 reps + 90 s rest) → 44 minutes. */
    val UPPER_A: StrengthWorkout = template("UPPER_A", "Upper A", StrengthWorkoutKind.UPPER) {
        reps("BARBELL_BENCH_PRESS", sets = 3, reps = 10)
        reps("BARBELL_ROW", sets = 3, reps = 10)
        reps("OVERHEAD_PRESS", sets = 3, reps = 10)
        reps("LAT_PULLDOWN", sets = 3, reps = 10)
        reps("BICEPS_CURL", sets = 3, reps = 10)
        reps("TRICEPS_PUSHDOWN", sets = 3, reps = 10)
    }

    val UPPER_B: StrengthWorkout = template("UPPER_B", "Upper B", StrengthWorkoutKind.UPPER) {
        reps("INCLINE_DUMBBELL_PRESS", sets = 3, reps = 10)
        reps("PULL_UP", sets = 3, reps = 8)
        reps("DUMBBELL_ROW", sets = 3, reps = 10)
        reps("LATERAL_RAISE", sets = 3, reps = 15, restSec = 60)
        reps("FACE_PULL", sets = 3, reps = 15, restSec = 60)
        reps("TRICEPS_DIP", sets = 3, reps = 10)
        reps("HAMMER_CURL", sets = 3, reps = 12, restSec = 60)
    }

    val LOWER_A: StrengthWorkout = template("LOWER_A", "Lower A", StrengthWorkoutKind.LOWER) {
        reps("BARBELL_BACK_SQUAT", sets = 4, reps = 8, restSec = 120)
        reps("ROMANIAN_DEADLIFT", sets = 3, reps = 10)
        reps("BULGARIAN_SPLIT_SQUAT", sets = 3, reps = 10)
        reps("LEG_CURL", sets = 3, reps = 12, restSec = 60)
        reps("HIP_THRUST", sets = 3, reps = 12)
        reps("CALF_RAISE", sets = 3, reps = 15, restSec = 60)
    }

    val LOWER_B: StrengthWorkout = template("LOWER_B", "Lower B", StrengthWorkoutKind.LOWER) {
        reps("FRONT_SQUAT", sets = 4, reps = 6, restSec = 120)
        reps("SINGLE_LEG_RDL", sets = 3, reps = 10)
        reps("WALKING_LUNGE", sets = 3, reps = 12)
        reps("LEG_PRESS", sets = 3, reps = 12)
        reps("NORDIC_HAMSTRING_CURL", sets = 3, reps = 6)
        reps("HIP_ADDUCTION", sets = 3, reps = 15, restSec = 60)
        reps("CALF_RAISE", sets = 3, reps = 20, restSec = 60)
    }

    val FULL_A: StrengthWorkout = template("FULL_A", "Full body A", StrengthWorkoutKind.FULL) {
        reps("GOBLET_SQUAT", sets = 3, reps = 12)
        reps("PUSH_UP", sets = 3, reps = 12)
        reps("ROMANIAN_DEADLIFT", sets = 3, reps = 10)
        reps("INVERTED_ROW", sets = 3, reps = 10)
        reps("OVERHEAD_PRESS", sets = 3, reps = 10)
        hold("PLANK", sets = 3, seconds = 45)
    }

    val CORE_A: StrengthWorkout = template("CORE_A", "Core A", StrengthWorkoutKind.CORE) {
        hold("PLANK", sets = 3, seconds = 60)
        hold("SIDE_PLANK", sets = 3, seconds = 45)
        hold("HOLLOW_HOLD", sets = 3, seconds = 30)
        reps("DEAD_BUG", sets = 3, reps = 10, restSec = 60)
        reps("BIRD_DOG", sets = 3, reps = 10, restSec = 60)
        hold("COPENHAGEN_PLANK", sets = 3, seconds = 30)
        reps("BACK_EXTENSION", sets = 3, reps = 12, restSec = 60)
    }

    /**
     * P17 (§P17): the legs-and-hips routine, prescribed on a rest day whose lower body is loaded.
     * 8 holds, 30–60 s, 15 s between them → 780 s of work + 480 s overhead = **21 min**.
     */
    val MOBILITY_LOWER_A: StrengthWorkout =
        template("MOBILITY_LOWER_A", "Mobility lower A", StrengthWorkoutKind.MOBILITY_LOWER) {
            flow("MOB_WORLDS_GREATEST_STRETCH", sets = 2, seconds = 45)
            flow("MOB_COUCH_STRETCH", sets = 2, seconds = 45)
            flow("MOB_PIGEON", sets = 2, seconds = 45)
            flow("MOB_HIP_SWITCH_90_90", sets = 1, seconds = 60)
            flow("MOB_STANDING_HAMSTRING_STRETCH", sets = 2, seconds = 45)
            flow("MOB_ADDUCTOR_ROCK_BACK", sets = 1, seconds = 45)
            flow("MOB_DEEP_SQUAT_HOLD", sets = 1, seconds = 60)
            flow("MOB_WALL_CALF_STRETCH", sets = 2, seconds = 30)
        }

    /**
     * P17: the shoulders-and-spine routine, for a loaded upper body on fresh legs.
     * 7 holds → 765 s + 480 s = 1245 s → **21 min**.
     */
    val MOBILITY_UPPER_A: StrengthWorkout =
        template("MOBILITY_UPPER_A", "Mobility upper A", StrengthWorkoutKind.MOBILITY_UPPER) {
            flow("MOB_CAT_COW", sets = 2, seconds = 45)
            flow("MOB_THREAD_THE_NEEDLE", sets = 2, seconds = 45)
            flow("MOB_THORACIC_ROTATION", sets = 2, seconds = 45)
            flow("MOB_CHILDS_POSE", sets = 1, seconds = 60)
            flow("MOB_DOORWAY_PEC_STRETCH", sets = 2, seconds = 45)
            flow("MOB_WALL_SLIDES", sets = 2, seconds = 45)
            flow("MOB_SHOULDER_CARS", sets = 2, seconds = 30)
        }

    /**
     * P17: the default routine — head to toe, and what a rest day gets when neither half of the
     * body stands out. 8 holds → 780 s + 480 s = 1260 s → **21 min**.
     */
    val MOBILITY_FULL_A: StrengthWorkout =
        template("MOBILITY_FULL_A", "Mobility full A", StrengthWorkoutKind.MOBILITY_FULL) {
            flow("MOB_WORLDS_GREATEST_STRETCH", sets = 2, seconds = 45)
            flow("MOB_CAT_COW", sets = 1, seconds = 60)
            flow("MOB_DOWNWARD_DOG", sets = 1, seconds = 60)
            flow("MOB_COUCH_STRETCH", sets = 2, seconds = 45)
            flow("MOB_STANDING_HAMSTRING_STRETCH", sets = 2, seconds = 45)
            flow("MOB_THREAD_THE_NEEDLE", sets = 2, seconds = 45)
            flow("MOB_CHILDS_POSE", sets = 1, seconds = 45)
            flow("MOB_SHOULDER_CARS", sets = 2, seconds = 30)
        }

    /** All nine, in the order the Workouts screen lists them and the seeder writes them. */
    val ALL: List<StrengthWorkout> = listOf(
        UPPER_A, UPPER_B, LOWER_A, LOWER_B, FULL_A, CORE_A,
        MOBILITY_LOWER_A, MOBILITY_UPPER_A, MOBILITY_FULL_A,
    )

    private val byTemplateId: Map<String, StrengthWorkout> = ALL.associateBy { it.templateId!! }

    /** The built-in with this `templateId` (`UPPER_A`, …), or `null` — suggestions name them. */
    fun byId(templateId: String): StrengthWorkout? = byTemplateId[templateId]

    /** The built-ins of one kind, for the alternating choice of §3.12.5. */
    fun ofKind(kind: StrengthWorkoutKind): List<StrengthWorkout> = ALL.filter { it.kind == kind }

    private fun template(
        templateId: String,
        name: String,
        kind: StrengthWorkoutKind,
        rows: TemplateBuilder.() -> Unit,
    ): StrengthWorkout = StrengthWorkout(
        id = 0L,
        name = name,
        kind = kind,
        templateId = templateId,
        isBuiltIn = true,
        exercises = TemplateBuilder().apply(rows).rows,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    /**
     * Builds the ordered rows of one template. `orderIndex` is the position in the list — the
     * repository renumbers from the list position too, so the two can never disagree.
     */
    private class TemplateBuilder {
        val rows = mutableListOf<StrengthWorkoutExercise>()

        /** A counted exercise. Rejects a held one, so `ex08` cannot be broken by an edit here. */
        fun reps(exerciseId: String, sets: Int, reps: Int, restSec: Int = DEFAULT_REST) {
            val exercise = requireCatalog(exerciseId)
            require(!exercise.isTimed) { "${exercise.id} is timed — prescribe it with hold()" }
            add(exercise, sets, reps = reps, seconds = null, restSec = restSec)
        }

        /**
         * P17: one mobility hold — a held exercise with the short [MOBILITY_REST] between rounds.
         * Refuses anything that is not [Exercise.isMobility], so a mobility template can never
         * quietly acquire a plank and start depositing muscle load.
         */
        fun flow(exerciseId: String, sets: Int, seconds: Int) {
            val exercise = requireCatalog(exerciseId)
            require(exercise.isMobility) { "${exercise.id} is not a mobility exercise" }
            require(seconds in MOBILITY_HOLD_RANGE) { "${exercise.id}: $seconds s is not 30-60 s" }
            hold(exerciseId, sets = sets, seconds = seconds, restSec = MOBILITY_REST)
        }

        /** A held exercise: `seconds`, never `reps`. */
        fun hold(exerciseId: String, sets: Int, seconds: Int, restSec: Int = SHORT_REST) {
            val exercise = requireCatalog(exerciseId)
            require(exercise.isTimed) { "${exercise.id} is counted — prescribe it with reps()" }
            add(exercise, sets, reps = null, seconds = seconds, restSec = restSec)
        }

        private fun add(exercise: Exercise, sets: Int, reps: Int?, seconds: Int?, restSec: Int) {
            rows += StrengthWorkoutExercise(
                id = 0L,
                workoutId = 0L,
                orderIndex = rows.size,
                exerciseId = exercise.id,
                sets = sets,
                reps = reps,
                seconds = seconds,
                isBodyweight = exercise.equipment == Equipment.BODYWEIGHT,
                restSec = restSec,
            )
        }

        private fun requireCatalog(exerciseId: String): Exercise =
            requireNotNull(ExerciseCatalog.byId(exerciseId)) {
                "Template names an exercise the catalog does not have: $exerciseId"
            }

        private companion object {
            /** §P17: a mobility hold is prescribed in the same 30-60 s the progression works in. */
            val MOBILITY_HOLD_RANGE = 30..60
        }
    }
}

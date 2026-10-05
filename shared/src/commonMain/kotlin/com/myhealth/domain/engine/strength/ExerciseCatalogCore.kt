package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment as Eq
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern as P
import com.myhealth.domain.model.MuscleGroup as M

/**
 * The trunk half of the catalog (PLAN §3.12.1): abs, obliques, the lower back and the one
 * adductor hold a runner actually needs. Split out of [ExerciseCatalog] for R10.
 *
 * Everything held rather than counted carries `isTimed = true`, and the templates prescribe those
 * rows with `seconds` instead of `reps` (`ex08`).
 */
internal val CORE_EXERCISES: List<Exercise> = listOf(
    Exercise(
        id = "PLANK",
        name = "Plank",
        primary = setOf(M.ABS),
        secondary = setOf(M.OBLIQUES, M.LOWER_BACK, M.SHOULDERS_FRONT),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        isTimed = true,
        cue = "Elbows under the shoulders, ribs down, glutes on — quality over minutes.",
    ),
    Exercise(
        id = "SIDE_PLANK",
        name = "Side plank",
        primary = setOf(M.OBLIQUES),
        secondary = setOf(M.ABS, M.GLUTES, M.SHOULDERS_FRONT),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        unilateral = true,
        isTimed = true,
        cue = "Stack the feet and the shoulders, push the bottom hip to the ceiling.",
    ),
    Exercise(
        id = "HOLLOW_HOLD",
        name = "Hollow hold",
        primary = setOf(M.ABS),
        secondary = setOf(M.OBLIQUES, M.QUADS),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        isTimed = true,
        cue = "Lower back flat on the floor; lower the legs only as far as that holds.",
    ),
    Exercise(
        id = "COPENHAGEN_PLANK",
        name = "Copenhagen plank",
        primary = setOf(M.ADDUCTORS),
        secondary = setOf(M.ABS, M.OBLIQUES),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        unilateral = true,
        isTimed = true,
        cue = "Top leg on the bench, hips high — the groin insurance for football.",
    ),
    Exercise(
        id = "DEAD_BUG",
        name = "Dead bug",
        primary = setOf(M.ABS),
        secondary = setOf(M.OBLIQUES, M.LOWER_BACK),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        cue = "Opposite arm and leg, slow; the lower back never leaves the floor.",
    ),
    Exercise(
        id = "BIRD_DOG",
        name = "Bird dog",
        primary = setOf(M.LOWER_BACK),
        secondary = setOf(M.GLUTES, M.ABS, M.SHOULDERS_REAR),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        unilateral = true,
        cue = "Reach long rather than high; the hips stay level with the floor.",
    ),
    Exercise(
        id = "HANGING_LEG_RAISE",
        name = "Hanging leg raise",
        primary = setOf(M.ABS),
        secondary = setOf(M.OBLIQUES, M.FOREARMS, M.LATS),
        equipment = Eq.BODYWEIGHT,
        pattern = P.CORE,
        cue = "Curl the pelvis up, do not just swing the legs; knees bent to regress.",
    ),
    Exercise(
        id = "RUSSIAN_TWIST",
        name = "Russian twist",
        primary = setOf(M.OBLIQUES),
        secondary = setOf(M.ABS),
        equipment = Eq.MEDICINE_BALL,
        pattern = P.CORE,
        cue = "Rotate through the ribs, not the arms; heels may stay down.",
    ),
    Exercise(
        id = "PALLOF_PRESS",
        name = "Pallof press",
        primary = setOf(M.OBLIQUES),
        secondary = setOf(M.ABS, M.GLUTES),
        equipment = Eq.CABLE,
        pattern = P.CORE,
        cue = "Resist the rotation — press straight out and refuse to turn.",
    ),
    Exercise(
        id = "BACK_EXTENSION",
        name = "Back extension",
        primary = setOf(M.LOWER_BACK),
        secondary = setOf(M.GLUTES, M.HAMSTRINGS),
        equipment = Eq.BODYWEIGHT,
        pattern = P.HINGE,
        cue = "Extend to a straight line and stop; no hinging into a backbend.",
    ),
)

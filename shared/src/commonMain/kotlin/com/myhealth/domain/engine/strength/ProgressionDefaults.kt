package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import kotlin.math.floor

/**
 * The constants of the load progression (PLAN §P16, P16.1): what a lift starts at, how many reps
 * it is prescribed for, and how big one step on the equipment is.
 *
 * Everything here is a table or a named constant — [ProgressionEngine] contains the rules and no
 * numbers, so a number the owner disagrees with is changed in exactly one place.
 *
 * **Rounding.** The initial estimate is rounded **down** to the equipment increment (a first
 * session that is too light is a warm-up; one that is too heavy is a failed set), every later load
 * is rounded **half-up** to it (§0 A4). Both go through [floorToIncrement] / [roundToIncrement],
 * whose steps (2.5 and 1.0 kg) are exactly representable, so no result carries binary noise.
 */
object ProgressionDefaults {

    /** The weight used when the database holds no measurement at all (§3.1.1's fail-safe). */
    const val FALLBACK_BODY_WEIGHT_KG: Double = 75.0

    /**
     * How far back the initial estimate looks for a weight. Deliberately a year rather than the
     * nutrition engine's 30 days: a stale weight is still a far better starting load than
     * [FALLBACK_BODY_WEIGHT_KG], and the estimate is replaced by the first feedback anyway.
     */
    const val BODY_WEIGHT_MAX_AGE_DAYS: Long = 365L

    /** Every lift the table below does not name starts here — deliberately conservative. */
    const val DEFAULT_RATIO: Double = 0.15

    /** `TOO_EASY` adds this many reps; the load step that follows at the top of the range. */
    const val TOO_EASY_REPS: Int = 2
    const val TOO_EASY_SECONDS: Int = 10
    const val TOO_EASY_LOAD_FRACTION: Double = 0.05

    /** `EASY` is the half step: one rep, and half the load bump when the range is exceeded. */
    const val EASY_REPS: Int = 1
    const val EASY_SECONDS: Int = 5
    const val EASY_LOAD_FRACTION: Double = 0.025

    /** `TOO_HARD` takes 5 % off, or two reps / ten seconds when there is no load to take off. */
    const val TOO_HARD_LOAD_FRACTION: Double = 0.05
    const val TOO_HARD_REPS: Int = 2
    const val TOO_HARD_SECONDS: Int = 10

    /** Body-weight ratios per exercise (§P16). Values for a **dumbbell pair are per hand**. */
    private val RATIOS: Map<String, Double> = mapOf(
        "BARBELL_BACK_SQUAT" to 0.50,
        "FRONT_SQUAT" to 0.40,
        "CONVENTIONAL_DEADLIFT" to 0.60,
        "ROMANIAN_DEADLIFT" to 0.45,
        "HIP_THRUST" to 0.60,
        "LEG_PRESS" to 1.00,
        "BARBELL_BENCH_PRESS" to 0.40,
        "INCLINE_DUMBBELL_PRESS" to 0.15,
        "OVERHEAD_PRESS" to 0.25,
        "BARBELL_ROW" to 0.35,
        "DUMBBELL_ROW" to 0.15,
        "LAT_PULLDOWN" to 0.40,
        "SEATED_CABLE_ROW" to 0.35,
        "BICEPS_CURL" to 0.10,
        "HAMMER_CURL" to 0.10,
        "TRICEPS_PUSHDOWN" to 0.20,
        "SKULL_CRUSHER" to 0.15,
        "KETTLEBELL_SWING" to 0.20,
        "GOBLET_SQUAT" to 0.20,
        "FARMERS_CARRY" to 0.25,
        "LEG_CURL" to 0.25,
        "LEG_EXTENSION" to 0.25,
    )

    /**
     * The exercises whose load is the weight of **one** implement. Their ratio is already the
     * per-hand ratio, so nothing is halved — the flag exists so the UI can print "per hand" and
     * so `pg12` can assert that a dumbbell row is not prescribed as a total.
     */
    private val PER_HAND: Set<String> = setOf(
        "INCLINE_DUMBBELL_PRESS",
        "DUMBBELL_ROW",
        "BICEPS_CURL",
        "HAMMER_CURL",
        "FARMERS_CARRY",
    )

    /** The smallest change that can actually be made with each kind of equipment, in kg. */
    private val INCREMENTS: Map<Equipment, Double> = mapOf(
        Equipment.BARBELL to 2.5,
        Equipment.MACHINE to 2.5,
        Equipment.CABLE to 2.5,
        Equipment.DUMBBELL to 1.0,
        Equipment.KETTLEBELL to 1.0,
        Equipment.BAND to 1.0,
        Equipment.MEDICINE_BALL to 1.0,
        // Bodyweight carries no load at all; the value is never used, but a lookup must not throw.
        Equipment.BODYWEIGHT to 1.0,
        // Likewise the foam roller (P17): a mobility drill never carries a load.
        Equipment.FOAM_ROLLER to 1.0,
    )

    /** Compound patterns: heavy and low-rep with a barbell, moderate with anything else. */
    private val COMPOUND_PATTERNS: Set<MovementPattern> = setOf(
        MovementPattern.SQUAT,
        MovementPattern.HINGE,
        MovementPattern.LUNGE,
        MovementPattern.HORIZONTAL_PUSH,
        MovementPattern.VERTICAL_PUSH,
        MovementPattern.HORIZONTAL_PULL,
        MovementPattern.VERTICAL_PULL,
    )

    private val BARBELL_COMPOUND_REPS = 5..8
    private val OTHER_COMPOUND_REPS = 8..12
    private val ISOLATION_REPS = 10..15
    private val CORE_REPS = 10..20
    private val PLYOMETRIC_REPS = 5..8
    private val HOLD_SECONDS = 30..60

    /** The body-weight fraction [exerciseId] starts from, [DEFAULT_RATIO] when it is not listed. */
    fun ratioFor(exerciseId: String): Double = RATIOS[exerciseId] ?: DEFAULT_RATIO

    /** True when the load of [exerciseId] is per hand rather than a total. */
    fun isPerHand(exerciseId: String): Boolean = exerciseId in PER_HAND

    /**
     * P17: whether [exercise] is prescribed with a weight at all. Bodyweight never is — and
     * neither is a mobility drill, whatever it is performed with: a light band round a
     * pull-apart or a foam roller is a tool, not a load, and §P17's mobility progression grows
     * the **hold**, not the kilos.
     */
    fun carriesLoad(exercise: Exercise): Boolean =
        exercise.equipment != Equipment.BODYWEIGHT && !exercise.isMobility

    /** The smallest usable load change for [equipment], in kg. */
    fun incrementKg(equipment: Equipment): Double = INCREMENTS.getValue(equipment)

    /** The same step for an exercise — what one notch on its progression is worth. */
    fun incrementKg(exercise: Exercise): Double = incrementKg(exercise.equipment)

    /**
     * The rep range a counted exercise is worked in. `MOBILITY` never reaches here (every
     * mobility entry is timed) and would fall through to the 8-12 default. A compound pattern
     * under a barbell is
     * 5–8, the same pattern with anything else (dumbbell, machine, cable, bodyweight — and, as a
     * documented extension of §P16, kettlebell / band / medicine ball) is 8–12; `ISOLATION`
     * 10–15, counted `CORE` 10–20, `PLYOMETRIC` 5–8. A counted `CARRY` — the catalog has none —
     * falls back to 8–12.
     */
    fun repRange(exercise: Exercise): IntRange = when (exercise.pattern) {
        in COMPOUND_PATTERNS ->
            if (exercise.equipment == Equipment.BARBELL) BARBELL_COMPOUND_REPS else OTHER_COMPOUND_REPS
        MovementPattern.ISOLATION -> ISOLATION_REPS
        MovementPattern.CORE -> CORE_REPS
        MovementPattern.PLYOMETRIC -> PLYOMETRIC_REPS
        else -> OTHER_COMPOUND_REPS
    }

    /**
     * The hold a timed exercise is worked in: 30–60 s for every pattern (§P16) — including the
     * `MOBILITY` pattern of §P17, whose whole progression is this range plus the ±10/±5 s steps.
     */
    @Suppress("UNUSED_PARAMETER")
    fun secondsRange(exercise: Exercise): IntRange = HOLD_SECONDS

    /**
     * [value] rounded **down** to a multiple of [step]. The epsilon absorbs the binary noise of
     * `0.35 * 80.0 = 28.000000000000004`, which would otherwise round down a whole increment.
     */
    fun floorToIncrement(value: Double, step: Double): Double =
        floor(value / step + FLOOR_EPSILON) * step

    /** [value] rounded **half-up** to a multiple of [step] (§0 A4). */
    fun roundToIncrement(value: Double, step: Double): Double = floor(value / step + 0.5) * step

    private const val FLOOR_EPSILON: Double = 1e-9
}

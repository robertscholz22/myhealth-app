package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.MuscleGroup.ABS
import com.myhealth.domain.model.MuscleGroup.ADDUCTORS
import com.myhealth.domain.model.MuscleGroup.BICEPS
import com.myhealth.domain.model.MuscleGroup.CALVES
import com.myhealth.domain.model.MuscleGroup.CHEST
import com.myhealth.domain.model.MuscleGroup.GLUTES
import com.myhealth.domain.model.MuscleGroup.HAMSTRINGS
import com.myhealth.domain.model.MuscleGroup.LATS
import com.myhealth.domain.model.MuscleGroup.LOWER_BACK
import com.myhealth.domain.model.MuscleGroup.QUADS
import com.myhealth.domain.model.MuscleGroup.SHOULDERS_FRONT
import com.myhealth.domain.model.MuscleGroup.SHOULDERS_REAR
import com.myhealth.domain.model.MuscleGroup.TRAPS
import com.myhealth.domain.model.MuscleGroup.TRICEPS
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.StrengthWorkout
import kotlin.math.abs

/**
 * The share tables of PLAN §3.12.4 — how one session's TRIMP is spread over the muscle groups it
 * actually works.
 *
 * Every row sums to **1.00** (checked at class-initialisation time), so a session deposits exactly
 * its own TRIMP and no more: `MuscleLoadEngine`'s per-group numbers stay in the same unit as the
 * load engine's, which is what makes the `0.35 × CTL` reference of §3.12.4 mean anything.
 *
 * Two families live here:
 * - **endurance** ([forSportGroup]) — a fixed table per [SportGroup]. `OTHER` is deliberately
 *   empty: an unknown sport says nothing about which muscles it loaded, and guessing would be
 *   worse than saying nothing (`ml12`).
 * - **strength without a workout** ([forStrengthSessionType]) — Garmin strength activities carry
 *   no exercise detail, so the linked planned session's [SessionType] picks a generic table.
 *   With a workout, `MuscleLoadEngine` uses the exercises themselves and never comes here.
 */
object MuscleDistribution {

    /** §3.12.4: running is a calf-first, whole-leg load with a little trunk. */
    val RUN: Map<MuscleGroup, Double> = mapOf(
        QUADS to 0.22,
        HAMSTRINGS to 0.20,
        GLUTES to 0.18,
        CALVES to 0.25,
        ADDUCTORS to 0.05,
        LOWER_BACK to 0.05,
        ABS to 0.05,
    )

    /** §3.12.4: soccer adds the cutting/sprinting adductor load running does not have. */
    val SOCCER: Map<MuscleGroup, Double> = mapOf(
        QUADS to 0.24,
        HAMSTRINGS to 0.22,
        GLUTES to 0.18,
        CALVES to 0.16,
        ADDUCTORS to 0.12,
        LOWER_BACK to 0.03,
        ABS to 0.05,
    )

    /** §3.12.4: the bike is quad-dominant and spares the calves. */
    val CYCLE: Map<MuscleGroup, Double> = mapOf(
        QUADS to 0.38,
        HAMSTRINGS to 0.16,
        GLUTES to 0.26,
        CALVES to 0.12,
        LOWER_BACK to 0.08,
    )

    /** §3.12.4: walking and hiking are mostly calves and posture. */
    val WALK: Map<MuscleGroup, Double> = mapOf(
        QUADS to 0.20,
        HAMSTRINGS to 0.15,
        GLUTES to 0.15,
        CALVES to 0.35,
        LOWER_BACK to 0.15,
    )

    /** §3.12.4: the only endurance row that is an upper-body load. */
    val SWIM: Map<MuscleGroup, Double> = mapOf(
        LATS to 0.28,
        SHOULDERS_FRONT to 0.14,
        SHOULDERS_REAR to 0.14,
        TRICEPS to 0.12,
        CHEST to 0.10,
        ABS to 0.10,
        GLUTES to 0.06,
        LOWER_BACK to 0.06,
    )

    /** §3.12.4: a `STRENGTH_UPPER` session with no exercise detail. */
    val STRENGTH_UPPER: Map<MuscleGroup, Double> = mapOf(
        CHEST to 0.20,
        LATS to 0.20,
        SHOULDERS_FRONT to 0.12,
        SHOULDERS_REAR to 0.10,
        TRICEPS to 0.12,
        BICEPS to 0.12,
        TRAPS to 0.07,
        ABS to 0.07,
    )

    /** §3.12.4: a `STRENGTH_LOWER` session with no exercise detail. */
    val STRENGTH_LOWER: Map<MuscleGroup, Double> = mapOf(
        QUADS to 0.30,
        GLUTES to 0.25,
        HAMSTRINGS to 0.22,
        CALVES to 0.10,
        ADDUCTORS to 0.08,
        LOWER_BACK to 0.05,
    )

    /**
     * §3.12.4's "`STRENGTH_FULL`/unknown = the mean of the two, renormalised". Both rows already
     * sum to 1.00, so the mean does too and the renormalisation is a no-op — it is applied anyway,
     * because the two tables are data and a later correction must not silently break the invariant.
     */
    val STRENGTH_FULL: Map<MuscleGroup, Double> = normalise(
        (STRENGTH_UPPER.keys + STRENGTH_LOWER.keys).associateWith { group ->
            ((STRENGTH_UPPER[group] ?: 0.0) + (STRENGTH_LOWER[group] ?: 0.0)) / 2.0
        },
    )

    /** Every table that must sum to 1.00 — the init check below, and `ml`-tests, read this. */
    val ENDURANCE_TABLES: Map<SportGroup, Map<MuscleGroup, Double>> = mapOf(
        SportGroup.RUN to RUN,
        SportGroup.SOCCER to SOCCER,
        SportGroup.CYCLE to CYCLE,
        SportGroup.WALK to WALK,
        SportGroup.SWIM to SWIM,
    )

    /** How far a row may sit from 1.00 before the class refuses to load. */
    private const val SUM_TOLERANCE: Double = 1e-9

    init {
        (ENDURANCE_TABLES.values + listOf(STRENGTH_UPPER, STRENGTH_LOWER, STRENGTH_FULL)).forEach {
            check(abs(it.values.sum() - 1.0) < SUM_TOLERANCE) {
                "MuscleDistribution row does not sum to 1.00: ${it.values.sum()}"
            }
        }
    }

    /**
     * The endurance share table of [group] — empty for `OTHER` (an unknown sport deposits nothing,
     * `ml12`) and for `STRENGTH`, whose sessions go through [forStrengthSessionType] or through
     * their own workout.
     */
    fun forSportGroup(group: SportGroup): Map<MuscleGroup, Double> =
        ENDURANCE_TABLES[group] ?: emptyMap()

    /**
     * The generic strength table for a session with no exercise detail: the `UPPER`/`LOWER` row for
     * those two types, the full-body mean for `STRENGTH_FULL`, for any other type and for `null`
     * (a Garmin "Strength" activity that no planned session ever claimed).
     */
    /**
     * P17 (§P17): a mobility routine deposits **no** muscle load — it is recovery, not work, and
     * a 20-minute stretch that made the legs read "loaded" would block the next leg day for the
     * wrong reason (`mob08`).
     *
     * True for a workout whose [StrengthWorkout.kind] is one of the three `MOBILITY_*` kinds, and
     * also for any workout every one of whose catalog rows is a mobility drill — a user's own
     * `CUSTOM` stretch routine counts too. A workout with no resolvable row at all is **not**
     * mobility: that case falls back to the generic session-type table, as it did before P17.
     */
    fun isMobilityOnly(workout: StrengthWorkout): Boolean {
        if (workout.kind.isMobility) return true
        val exercises = workout.exercises.mapNotNull { ExerciseCatalog.byId(it.exerciseId) }
        return exercises.isNotEmpty() && exercises.all { it.isMobility }
    }

    fun forStrengthSessionType(sessionType: SessionType?): Map<MuscleGroup, Double> =
        when (sessionType) {
            SessionType.STRENGTH_UPPER -> STRENGTH_UPPER
            SessionType.STRENGTH_LOWER -> STRENGTH_LOWER
            else -> STRENGTH_FULL
        }

    private fun normalise(shares: Map<MuscleGroup, Double>): Map<MuscleGroup, Double> {
        val total = shares.values.sum()
        if (total <= 0.0) return emptyMap()
        return shares.mapValues { (_, share) -> share / total }
    }
}

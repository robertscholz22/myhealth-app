package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.StrengthWorkout
import kotlin.math.max
import kotlin.math.pow

/**
 * One session as the muscle-load engine sees it (PLAN §3.12.4). Assembled by the caller — the
 * repository joins the completed activity to the planned session that claimed it and to that
 * session's workout — so `domain/` stays free of Room and of any I/O.
 *
 * [trimp] is the session's own training load in AU; [sessionType] is the *planned* type when the
 * activity was linked to one (a Garmin "Strength" activity carries no exercise detail, so its
 * planned type is the only thing that says which half of the body it was), and [workout] is the
 * exercise list when the session had one.
 */
data class MuscleSession(
    val day: Long,
    val sportGroup: SportGroup,
    val sessionType: SessionType? = null,
    val trimp: Double,
    val workout: StrengthWorkout? = null,
)

/**
 * Everything [MuscleLoadEngine.compute] reads (§3.12.4): the day the load is evaluated on, the
 * athlete's latest `daily_load.ctl` (the reference the bands are relative to) and the sessions of
 * the last [MuscleLoadEngine.WINDOW_DAYS] days.
 *
 * [today] and [MuscleSession.day] are epoch days, like every other day in the app.
 */
data class MuscleLoadInput(
    val today: Long,
    val ctl: Double,
    val sessions: List<MuscleSession> = emptyList(),
)

/**
 * The per-group state of the athlete's musculature on one day (§3.12.4).
 *
 * [byGroup] carries **every** [MuscleGroup], zero included, so the body figure (§3.12.2) can map
 * it straight onto its silhouettes; [bands] is the same map put through [MuscleLoadEngine.bandFor].
 * [lowerBody] and [upperBody] are the band of the *most loaded* group of each half — the two facts
 * `C15` and `Scorer.muscleBonus` actually read ("legs are cooked, arms are fine").
 */
data class MuscleLoadState(
    val byGroup: Map<MuscleGroup, Double>,
    val bands: Map<MuscleGroup, MuscleLoadBand>,
    /** `max(0.35 × ctl, 12.0)` — what "loaded" means for *this* athlete. */
    val ref: Double,
    val lowerBody: MuscleLoadBand,
    val upperBody: MuscleLoadBand,
) {
    fun loadOf(group: MuscleGroup): Double = byGroup[group] ?: 0.0

    fun bandOf(group: MuscleGroup): MuscleLoadBand = bands[group] ?: MuscleLoadBand.FRESH

    /** The most loaded of the five lower-body groups, in AU. */
    val lowerBodyLoad: Double get() = maxLoad(lowerBody = true)

    /** The most loaded of the eleven groups that are not lower body, in AU. */
    val upperBodyLoad: Double get() = maxLoad(lowerBody = false)

    /** `load / ref`, clamped to `0..1`: the heat-map intensity of §3.12.2. */
    fun intensityOf(group: MuscleGroup): Double =
        if (ref <= 0.0) 0.0 else (loadOf(group) / ref).coerceIn(0.0, 1.0)

    private fun maxLoad(lowerBody: Boolean): Double = byGroup
        .filterKeys { it.isLowerBody == lowerBody }
        .values
        .maxOrNull()
        ?: 0.0
}

/**
 * Body-part specific load (PLAN §3.12.4) — the engine behind the owner's "no leg day after an
 * intense run, but upper body would be ok".
 *
 * Every session, endurance or strength, deposits its TRIMP onto muscle groups through
 * [MuscleDistribution] (or, for a strength session with an actual workout, through the exercises'
 * own primary/secondary sets), the deposits decay with a **48-hour half-life**, and what is left on
 * the evaluation day is compared against `ref = max(0.35 × ctl, 12.0)`.
 *
 * There is deliberately **no cache table** (§2.2.7): the whole computation is a few dozen
 * multiplications over ≤ 14 days of sessions, and a cache would add a table, an invalidation path
 * and a migration risk for microseconds of work.
 *
 * Ambiguity notes (§3.12.4 leaves these open):
 * - A session dated **after** [MuscleLoadInput.today] contributes nothing: the engine reports the
 *   athlete's state now, not a projection (the suggester projects forward itself, by decaying
 *   [MuscleLoadState.lowerBodyLoad] — see `StrengthRules`).
 * - A workout whose rows name no catalog exercise (every id dropped) falls back to the generic
 *   session-type table rather than depositing nothing.
 * - `ml03`'s quoted values assume a two-secondary barbell row; the catalog's row has four, so the
 *   computed values differ — see the PLAN §3.12.4 note.
 */
object MuscleLoadEngine {

    /** §3.12.4: sessions older than this are outside the window (and, after 14 days, ≈ 0 anyway). */
    const val WINDOW_DAYS: Long = 14L

    /** §3.12.4: half of a deposit is gone after 48 hours. */
    const val HALF_LIFE_DAYS: Double = 2.0

    /** §3.12.4: the share the single hardest-hit group takes on a typical day. */
    const val REF_CTL_SHARE: Double = 0.35

    /** §3.12.4: …and the floor that stops a fresh install from calling every group fatigued. */
    const val REF_FLOOR_AU: Double = 12.0

    /** §3.12.4: below `0.75 × ref` a group is fresh, above `1.50 × ref` it is fatigued. */
    const val FRESH_BELOW: Double = 0.75
    const val FATIGUED_ABOVE: Double = 1.50

    /** A secondary muscle takes half the share of a primary one (§3.12.4). */
    const val SECONDARY_WEIGHT: Double = 0.5

    /** `ref = max(0.35 × ctl, 12.0)`. */
    fun referenceFor(ctl: Double): Double = max(REF_CTL_SHARE * ctl, REF_FLOOR_AU)

    /** The 48-hour half-life factor after [ageDays] days. */
    fun decay(ageDays: Double): Double = 0.5.pow(ageDays / HALF_LIFE_DAYS)

    /** §3.12.4's three bands, relative to [ref]. */
    fun bandFor(load: Double, ref: Double): MuscleLoadBand = when {
        load < FRESH_BELOW * ref -> MuscleLoadBand.FRESH
        load > FATIGUED_ABOVE * ref -> MuscleLoadBand.FATIGUED
        else -> MuscleLoadBand.LOADED
    }

    /**
     * How one session's TRIMP is spread over the groups it worked — the share table for an
     * endurance session, the exercises themselves for a strength session that has a workout, the
     * generic `STRENGTH_*` table for one that does not — and **nothing at all** for a P17 mobility
     * routine ([MuscleDistribution.isMobilityOnly]).
     */
    fun sharesOf(session: MuscleSession): Map<MuscleGroup, Double> {
        if (session.sportGroup != SportGroup.STRENGTH) {
            return MuscleDistribution.forSportGroup(session.sportGroup)
        }
        val workout = session.workout
            ?: return MuscleDistribution.forStrengthSessionType(session.sessionType)
        // P17: a mobility routine is recovery and deposits nothing at all.
        if (MuscleDistribution.isMobilityOnly(workout)) return emptyMap()
        return workoutShares(workout)
            ?: MuscleDistribution.forStrengthSessionType(session.sessionType)
    }

    /** The AU each group takes from one session, before any decay. */
    fun depositOf(session: MuscleSession): Map<MuscleGroup, Double> {
        if (session.trimp <= 0.0) return emptyMap()
        return sharesOf(session).mapValues { (_, share) -> session.trimp * share }
    }

    /**
     * §3.12.4's `w(g) = Σ sets × (1.0 primary | 0.5 secondary)`, normalised to a share table. A row
     * naming an id the catalog no longer has is skipped; if that leaves nothing at all, the caller
     * ([sharesOf]) falls back to the generic table.
     */
    fun workoutShares(workout: StrengthWorkout): Map<MuscleGroup, Double>? {
        val weights = mutableMapOf<MuscleGroup, Double>()
        workout.exercises.forEach { row ->
            val exercise = ExerciseCatalog.byId(row.exerciseId) ?: return@forEach
            val sets = row.sets.toDouble()
            exercise.primary.forEach { weights[it] = (weights[it] ?: 0.0) + sets }
            exercise.secondary.forEach { weights[it] = (weights[it] ?: 0.0) + sets * SECONDARY_WEIGHT }
        }
        val total = weights.values.sum()
        if (total <= 0.0) return null
        return weights.mapValues { (_, weight) -> weight / total }
    }

    /** The whole of §3.12.4: deposit, decay, sum, band. */
    fun compute(input: MuscleLoadInput): MuscleLoadState {
        val ref = referenceFor(input.ctl)
        val loads = MuscleGroup.entries.associateWith { 0.0 }.toMutableMap()
        input.sessions.forEach { session ->
            val age = input.today - session.day
            if (age < 0L || age > WINDOW_DAYS) return@forEach
            val factor = decay(age.toDouble())
            depositOf(session).forEach { (group, au) ->
                loads[group] = (loads[group] ?: 0.0) + au * factor
            }
        }
        val byGroup = loads.toMap()
        val bands = byGroup.mapValues { (_, load) -> bandFor(load, ref) }
        val lower = byGroup.filterKeys { it.isLowerBody }.values.maxOrNull() ?: 0.0
        val upper = byGroup.filterKeys { !it.isLowerBody }.values.maxOrNull() ?: 0.0
        return MuscleLoadState(
            byGroup = byGroup,
            bands = bands,
            ref = ref,
            lowerBody = bandFor(lower, ref),
            upperBody = bandFor(upper, ref),
        )
    }
}

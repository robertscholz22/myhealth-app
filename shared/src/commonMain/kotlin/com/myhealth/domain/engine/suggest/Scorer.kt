package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.TrainingPhase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The five weighted terms of PLAN §3.5.5 plus their weighted sum. */
data class ScoreBreakdown(
    val goalFit: Double,
    val loadFit: Double,
    val recoveryFit: Double,
    val spacingFit: Double,
    val prefFit: Double,
    /** P11.2's `+0.10` phase nudge; `0.0` without cycle data, so the weighted sum is unchanged. */
    val cycleBonus: Double = 0.0,
    /** P14.5's `+0.10` muscle nudge (§3.12.5); `0.0` without muscle load, for the same reason. */
    val muscleBonus: Double = 0.0,
) {
    val total: Double
        get() = min(
            1.0,
            Scorer.W_GOAL * goalFit +
                Scorer.W_LOAD * loadFit +
                Scorer.W_RECOVERY * recoveryFit +
                Scorer.W_SPACING * spacingFit +
                Scorer.W_PREF * prefFit +
                cycleBonus +
                muscleBonus,
        )
}

/** Everything [Scorer.score] needs besides the candidate and the grid. */
data class ScoringContext(
    val phase: TrainingPhase,
    val preferredTypes: Set<SessionType>,
    /** The sport of the highest-priority goal that *has* a sport; `null` ⇒ no sport preference. */
    val primaryGoalGroup: SportGroup?,
    val secondaryGoalGroups: Set<SportGroup> = emptySet(),
    val remainingBudget: Double,
    val recoveryBand: RecoveryBand? = null,
    val weeklyCaps: Map<SportGroup, Int> = emptyMap(),
    /** P11.2: the horizon's cycle statuses; empty when tracking is off. */
    val cycleStatusByDay: Map<Long, CycleStatus> = emptyMap(),
    /** P14.5: the muscle-load facts [Scorer.muscleBonus] reads; `NONE` scores a flat `0.0`. */
    val muscle: MuscleContext = MuscleContext.NONE,
)

/**
 * Candidate scoring (PLAN §3.5.5). Every term is in `[0,1]` and the weights sum to 1, so `score`
 * is directly comparable to §3.5.6's `0.35` placement threshold.
 *
 * Ambiguity notes (§3.5.5 leaves these open):
 * - Goals carry no sport column (§2.2.4), so a goal's sport is derived from its [GoalType]:
 *   `RACE_TIME` → run, `SOCCER_AVAILABILITY` → soccer, `STRENGTH_LIFT` → strength, and
 *   `BODY_WEIGHT`/`CONSISTENCY` → none. When *no* goal implies a sport (or there are no goals at
 *   all), `goalFit` falls back to "the phase's preferred types are what matters": 1.0 for a
 *   preferred type, 0.6 otherwise — otherwise every candidate would score the 0.1 "otherwise" row
 *   and a goal-less week would be shaped by nothing at all (test `sug20`).
 * - "gap to the nearest same-intensity session" is measured in whole days against every item in
 *   the grid (fixed events included), which is the only information a day-granular plan has.
 * - `OFF_SEASON` has no row in the §3.5.5 preference table; cross-training, full-body strength and
 *   mobility are used.
 * - P11.2 adds a sixth, unweighted term: [ScoreBreakdown.cycleBonus] is added on top of the
 *   weighted sum and the total is then capped at 1.0, so the score stays comparable to the 0.35
 *   placement threshold and a run without cycle data scores exactly what it did before.
 * - P14.5 adds a seventh on exactly the same seam: [ScoreBreakdown.muscleBonus] (§3.12.5), `0.0`
 *   whenever `SuggestionInput.muscleLoad` is `null`, so every pre-P14.5 score is unchanged.
 */
object Scorer {

    const val W_GOAL: Double = 0.35
    const val W_LOAD: Double = 0.25
    const val W_RECOVERY: Double = 0.20
    const val W_SPACING: Double = 0.10
    const val W_PREF: Double = 0.10

    /** `loadFit` is zero once a candidate would overshoot the remaining budget by 40 %. */
    const val LOAD_OVERSHOOT_FACTOR: Double = 1.4

    /** Ideal spacing between same-intensity sessions, in days (72 h for hard work, 24 h else). */
    const val HARD_IDEAL_SPACING_DAYS: Double = 3.0
    const val EASY_IDEAL_SPACING_DAYS: Double = 1.0

    /** §3.5.5: in a taper, interval work keeps the stimulus but only 60 % of the duration. */
    const val TAPER_INTERVAL_DURATION_FACTOR: Double = 0.6

    /** Phase → preferred session types (§3.5.5). */
    fun preferredTypes(phase: TrainingPhase): Set<SessionType> = when (phase) {
        TrainingPhase.BASE -> setOf(SessionType.EASY_RUN, SessionType.LONG_RUN, SessionType.STRENGTH_FULL)
        TrainingPhase.BUILD -> setOf(SessionType.TEMPO_RUN, SessionType.LONG_RUN, SessionType.STRENGTH_LOWER)
        TrainingPhase.PEAK -> setOf(SessionType.INTERVAL_RUN, SessionType.TEMPO_RUN, SessionType.LONG_RUN)
        TrainingPhase.TAPER -> setOf(SessionType.EASY_RUN, SessionType.INTERVAL_RUN)
        TrainingPhase.RACE_WEEK -> setOf(SessionType.RECOVERY_RUN, SessionType.EASY_RUN, SessionType.MOBILITY)
        TrainingPhase.IN_SEASON -> setOf(SessionType.EASY_RUN, SessionType.STRENGTH_UPPER, SessionType.MOBILITY)
        TrainingPhase.RECOVERY_WEEK ->
            setOf(SessionType.RECOVERY_RUN, SessionType.EASY_RUN, SessionType.MOBILITY)
        TrainingPhase.OFF_SEASON ->
            setOf(SessionType.CROSS_TRAINING, SessionType.STRENGTH_FULL, SessionType.MOBILITY)
    }

    /** The session types a taper keeps but shortens (§3.5.5's "short INTERVAL_RUN", P12.3's rides). */
    val TAPER_SHORTENED_TYPES: Set<SessionType> =
        setOf(SessionType.INTERVAL_RUN, SessionType.BIKE_INTERVALS)

    /** The duration multiplier the phase applies to a session type (§3.5.5's "short INTERVAL_RUN"). */
    fun durationFactor(phase: TrainingPhase, sessionType: SessionType): Double =
        if (phase == TrainingPhase.TAPER && sessionType in TAPER_SHORTENED_TYPES) {
            TAPER_INTERVAL_DURATION_FACTOR
        } else {
            1.0
        }

    /**
     * The phase's preferred types for a given primary goal sport: P12.3's cycling table when the
     * athlete is riding towards a `CYCLE` goal, §3.5.5's running table otherwise.
     */
    fun preferredTypesFor(phase: TrainingPhase, primaryGoalGroup: SportGroup?): Set<SessionType> =
        if (primaryGoalGroup == SportGroup.CYCLE) {
            Periodization.bikePreferredTypes(phase)
        } else {
            preferredTypes(phase)
        }

    /** The sport group a goal implies, or `null` for goals that are sport-agnostic. */
    fun goalSportGroup(goal: Goal): SportGroup? = when (goal.type) {
        GoalType.RACE_TIME -> SportGroup.RUN
        GoalType.SOCCER_AVAILABILITY -> SportGroup.SOCCER
        GoalType.STRENGTH_LIFT -> SportGroup.STRENGTH
        GoalType.BIKE_FTP, GoalType.BIKE_VOLUME, GoalType.BIKE_EVENT -> SportGroup.CYCLE
        GoalType.BODY_WEIGHT, GoalType.CONSISTENCY -> null
    }

    /** The `recovery.band × intensity` matrix of §3.5.5. */
    fun recoveryFit(band: RecoveryBand?, intensity: Intensity): Double {
        val row = when (band) {
            RecoveryBand.FRESH -> listOf(0.4, 0.7, 0.9, 1.0, 1.0)
            RecoveryBand.GOOD -> listOf(0.5, 0.8, 1.0, 0.9, 0.8)
            RecoveryBand.MODERATE -> listOf(0.7, 1.0, 0.8, 0.5, 0.3)
            RecoveryBand.FATIGUED -> listOf(1.0, 0.8, 0.3, 0.0, 0.0)
            RecoveryBand.STRAINED -> listOf(1.0, 0.3, 0.0, 0.0, 0.0)
            null -> listOf(0.6, 0.9, 0.9, 0.7, 0.5)
        }
        return row[intensity.ordinal]
    }

    /** §3.5.5's `goalFit`. */
    fun goalFit(candidate: Candidate, ctx: ScoringContext): Double {
        val preferred = candidate.sessionType in ctx.preferredTypes
        val primary = ctx.primaryGoalGroup ?: return if (preferred) 1.0 else 0.6
        return when {
            candidate.sportGroup == primary && preferred -> 1.0
            candidate.sportGroup == primary -> 0.6
            candidate.sportGroup in ctx.secondaryGoalGroups -> 0.3
            else -> 0.1
        }
    }

    /** §3.5.5's `loadFit`. */
    fun loadFit(estTrimp: Double, remainingBudget: Double): Double {
        if (estTrimp > remainingBudget * LOAD_OVERSHOOT_FACTOR) return 0.0
        val denominator = max(remainingBudget, 1.0)
        return 1.0 - clamp01(abs(remainingBudget - estTrimp) / denominator)
    }

    /** §3.5.5's `spacingFit`: 1.0 at the ideal gap, linearly down to 0.0 at half of it. */
    fun spacingFit(candidate: Candidate, day: Long, grid: SuggestionGrid): Double {
        val ideal = if (candidate.isHard) HARD_IDEAL_SPACING_DAYS else EASY_IDEAL_SPACING_DAYS
        val gap = grid.entries()
            .filter { it.second.intensity == candidate.intensity }
            .minOfOrNull { abs(it.first - day).toDouble() }
            ?: return 1.0
        val half = ideal / 2.0
        return clamp01((gap - half) / half)
    }

    /** §3.5.5's `prefFit`: how much of the sport's weekly cap is still unused. */
    fun prefFit(candidate: Candidate, day: Long, grid: SuggestionGrid, ctx: ScoringContext): Double {
        val cap = ctx.weeklyCaps[candidate.sportGroup] ?: return 1.0
        if (cap <= 0) return 0.0
        val used = sessionsThisWeekForSport(candidate.sportGroup, day, grid)
        return clamp01(1.0 - used.toDouble() / cap)
    }

    /** Sessions of [group] already in the busiest rolling week containing [day]. */
    fun sessionsThisWeekForSport(group: SportGroup, day: Long, grid: SuggestionGrid): Int = grid
        .rollingWindows()
        .filter { day in it }
        .maxOfOrNull { window ->
            grid.days.filter { it.day in window }
                .sumOf { plan -> plan.sessions.count { it.sportGroup == group } }
        }
        ?: 0

    /** The weighted score of §3.5.5 for [candidate] on its own day. */
    fun score(candidate: Candidate, grid: SuggestionGrid, ctx: ScoringContext): ScoreBreakdown {
        val cycle = ctx.cycleStatusByDay[candidate.day]
        return ScoreBreakdown(
            goalFit = goalFit(candidate, ctx),
            loadFit = loadFit(candidate.estTrimp, ctx.remainingBudget),
            recoveryFit = recoveryFit(ctx.recoveryBand, candidate.intensity) *
                CycleRules.recoveryFactor(cycle, candidate.intensity),
            spacingFit = spacingFit(candidate, candidate.day, grid),
            prefFit = prefFit(candidate, candidate.day, grid, ctx),
            cycleBonus = CycleRules.scoreBonus(
                status = cycle,
                sessionType = candidate.sessionType,
                sportGroup = candidate.sportGroup,
                intensity = candidate.intensity,
            ),
            muscleBonus = muscleBonus(candidate, grid, ctx),
        )
    }

    /**
     * §3.12.5's `muscleBonus`: `+0.10` for an upper-body day on loaded legs, `+0.10` for a leg day
     * on fresh legs with nothing hard ahead. Unweighted — [ScoreBreakdown.total] adds it on top and
     * caps at 1.0 — and flat `0.0` without muscle load, which is what keeps every existing score
     * byte-identical (`sug39`).
     */
    fun muscleBonus(candidate: Candidate, grid: SuggestionGrid, ctx: ScoringContext): Double =
        StrengthRules.scoreBonus(candidate, candidate.day, grid, ctx.muscle)

    /** The scoring context for one run of the engine (§3.5.6 step 3). */
    fun contextOf(input: SuggestionInput, result: PeriodizationResult, remainingBudget: Double): ScoringContext {
        val groups = input.goals.sortedBy { it.priority }.mapNotNull { goalSportGroup(it) }
        val primary = groups.firstOrNull()
        return ScoringContext(
            phase = result.phase,
            preferredTypes = StrengthRules.poolPreferred(preferredTypesFor(result.phase, primary), input.strengthPool),
            primaryGoalGroup = primary,
            secondaryGoalGroups = groups.drop(1).toSet(),
            remainingBudget = remainingBudget,
            recoveryBand = result.band,
            weeklyCaps = SportPreferences.capsOf(input.profile.preferredSportsJson),
            cycleStatusByDay = input.cycleStatusByDay,
            muscle = MuscleContext.of(input),
        )
    }

    private fun clamp01(value: Double): Double = max(0.0, min(value, 1.0))
}

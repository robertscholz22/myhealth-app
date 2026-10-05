package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.math.abs
import com.myhealth.domain.util.epochDayDate

/**
 * The cycling half of the suggester (PLAN §3.5.8, P12.3). Three rules, all of them inert for an
 * athlete who neither caps a `CYCLE` sport nor carries a `BIKE_*` goal — which is what keeps
 * `sug01`…`sug26` byte-identical (test `sug28`).
 *
 * | Rule | Effect |
 * |---|---|
 * | gating | the four bike catalog rows are candidates only when [BikeContext.enabled] |
 * | `BIKE_INDOOR_SEASON` | November–March, with a trainer, every `CYCLE` candidate is `CYCLING_INDOOR` |
 * | `C14` | ≥ 3 days between two `BIKE_INTERVALS`, ≥ 2 days between one and any other hard item |
 *
 * Ambiguity notes (the plan leaves these open):
 * - "indoor season" is evaluated **per candidate day**, not once per batch, so a horizon that
 *   crosses 31 October or 31 March gets outdoor rides on one side of the boundary and trainer
 *   rides on the other.
 * - `TRAINER_SESSION` is a *structured* trainer workout, so it is offered year-round — but only
 *   with [BikeContext.trainerAvailable]; without the equipment the row is never a candidate.
 * - "every `CYCLE` candidate becomes `CYCLING_INDOOR`" is read literally and therefore also moves
 *   `CROSS_TRAINING` (a `CYCLING` row since §3.5.4) onto the trainer in season. `RECOVERY_SPIN`
 *   and `TRAINER_SESSION` are indoor to begin with and are unaffected.
 * - `C14`'s second half is symmetric: a hard run is kept two days clear of a bike interval session
 *   exactly as a bike interval session is kept clear of a hard run. The greedy loop places one
 *   session at a time and does not know which of the two it will meet first.
 */
object BikeRules {

    /** Goals that make cycling worth planning for, whatever the sport caps say. */
    val BIKE_GOAL_TYPES: Set<GoalType> = setOf(GoalType.BIKE_FTP, GoalType.BIKE_VOLUME, GoalType.BIKE_EVENT)

    /** November … March: the months the planner prefers the trainer (owner decision, §P12). */
    val INDOOR_MONTHS: Set<Month> = setOf(
        Month.NOVEMBER,
        Month.DECEMBER,
        Month.JANUARY,
        Month.FEBRUARY,
        Month.MARCH,
    )

    /** C14: two `BIKE_INTERVALS` sessions need 72 h between them. */
    const val BIKE_INTERVAL_SPACING_DAYS: Long = 3L

    /** C14: a `BIKE_INTERVALS` session needs 48 h from any other hard item. */
    const val BIKE_HARD_SPACING_DAYS: Long = 2L

    /** True on days in [INDOOR_MONTHS] — the "trainer weather" half of the indoor rule. */
    fun isIndoorSeason(day: Long): Boolean = day.epochDayDate().month in INDOOR_MONTHS

    /** §P12.3's gate: a `CYCLE` cap above zero, or an active `BIKE_*` goal. */
    fun isBikeEnabled(preferredSportsJson: String, goals: List<Goal>): Boolean {
        val cap = SportPreferences.capsOf(preferredSportsJson)[SportGroup.CYCLE] ?: 0
        if (cap > 0) return true
        return goals.any { it.status == GoalStatus.ACTIVE && it.type in BIKE_GOAL_TYPES }
    }

    /** The highest-priority active `BIKE_*` goal, which is what the `BIKE_*` rationale names. */
    fun primaryBikeGoal(goals: List<Goal>): Goal? = goals
        .filter { it.status == GoalStatus.ACTIVE && it.type in BIKE_GOAL_TYPES }
        .minByOrNull { it.priority }

    /**
     * The catalog row [entry] actually becomes on [day], or `null` when it may not be offered at
     * all (a `TRAINER_SESSION` without a trainer).
     */
    fun entryFor(entry: CatalogEntry, day: Long, ctx: BikeContext): CatalogEntry? = when {
        entry.sessionType == SessionType.TRAINER_SESSION && !ctx.trainerAvailable -> null
        movesIndoors(entry, day, ctx) -> entry.copy(sportType = SportType.CYCLING_INDOOR)
        else -> entry
    }

    /** True when [entry] on [day] is an outdoor `CYCLE` row the trainer rule moves inside. */
    fun movesIndoors(entry: CatalogEntry, day: Long, ctx: BikeContext): Boolean =
        ctx.trainerAvailable && entry.sportType == SportType.CYCLING && isIndoorSeason(day)

    /**
     * `C14` — the bike-specific spacing of §3.5.3. Evaluated against every grid item, fixed or
     * suggested, in the same whole-day reading `C11` uses.
     */
    fun violatesSpacing(candidate: Candidate, day: Long, grid: SuggestionGrid): Boolean {
        val isIntervals = candidate.sessionType == SessionType.BIKE_INTERVALS
        if (!isIntervals && !candidate.isHard) return false
        return grid.entries().any { (otherDay, item) ->
            if (otherDay == day) return@any false
            val gap = abs(otherDay - day)
            val otherIsIntervals = item.sessionType == SessionType.BIKE_INTERVALS
            when {
                isIntervals && otherIsIntervals -> gap < BIKE_INTERVAL_SPACING_DAYS
                isIntervals && item.isHard -> gap < BIKE_HARD_SPACING_DAYS
                otherIsIntervals -> gap < BIKE_HARD_SPACING_DAYS
                else -> false
            }
        }
    }
}

/**
 * Whether this athlete's week may contain rides at all, and whether the rides can go indoors —
 * the two facts every bike rule hangs off (§3.5.8).
 */
data class BikeContext(val enabled: Boolean, val trainerAvailable: Boolean) {
    companion object {
        /** No cycling: the state every pre-P12 input is in, where the bike rules do nothing. */
        val NONE: BikeContext = BikeContext(enabled = false, trainerAvailable = false)

        fun of(input: SuggestionInput): BikeContext = BikeContext(
            enabled = BikeRules.isBikeEnabled(input.profile.preferredSportsJson, input.goals),
            trainerAvailable = input.profile.indoorTrainerAvailable,
        )
    }
}

package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.TrainingPhase
import kotlin.math.max
import kotlin.math.min

/**
 * Training phase + weekly load target (PLAN §3.5.2), the two numbers every suggestion hangs off.
 *
 * Stateless and pure: [compute] reads only its [SuggestionInput]. The weekly target is deliberately
 * conservative — the 25 % ramp cap, the ACWR multiplier and the recovery multipliers can only ever
 * *lower* it (risk R13).
 *
 * Ambiguity notes (§3.5.2 does not say):
 * - A race whose `targetDay` is in the past is ignored (it cannot be periodized towards), so the
 *   phase falls back to `IN_SEASON`/`BASE` exactly as if no race goal existed.
 * - `recentLoad.last()` is read as "the row with the largest `day`", so an unsorted list is safe.
 * - P11.2's late-luteal `×0.90` is applied in [compute], after [weeklyTarget], so the §3.5.2
 *   formula itself (and the tests that pin it) is untouched. Like every other multiplier here it
 *   can only lower the budget.
 * - `lastWeekActual` sums the seven days **before** today (`[today-7, today-1]`); today itself is
 *   still being planned and must not shrink its own budget.
 *
 * 0.9.1 (P19.6): the down week is no longer every 4th week since the plan started. [downWeekReason]
 * checks the load history every week and only takes one when there is a reason; the phase factors
 * were raised so that training at the target actually builds CTL (≈ 3 %/week in `BASE`, ≈ 5 % in
 * `BUILD` — a 28-day EWMA only grows by `(factor − 1) / 4` per week, so 1.05 meant ≈ 1 %).
 *
 * BUG-21: "the usual week" is [weekLevel] — `7 ×` the **mean** CTL of seven days — instead of one
 * day's CTL. CTL jumps on a training day and loses ~7 % on every rest day, so a single day's value
 * depended on the weekday: the long-build check measured every week against its last day's CTL
 * (the low point when the main session is early in the week), every normal week passed, and steady
 * training got a recovery week every week. An accepted down week now counts as the lighter week
 * ([downWeekReason]'s `lastDownWeekStartDay`), acute overload compares against the heaviest level
 * of the last six weeks (returning from a break is not an overload), and the ramp cap always allows
 * the usual week (after a light week the target returns to normal instead of staying capped).
 */
object Periodization {

    /** Inclusive upper bounds, in days-to-race, of the phases §3.5.2 tabulates. */
    const val RACE_WEEK_MAX_DAYS: Long = 7L
    const val TAPER_MAX_DAYS: Long = 10L
    const val PEAK_MAX_DAYS: Long = 35L
    const val BUILD_MAX_DAYS: Long = 77L

    /** A soccer match inside this window puts a goal-less athlete in season. */
    const val IN_SEASON_MATCH_WINDOW_DAYS: Long = 21L

    /**
     * P19.6 down-week check. Weeks are the rolling seven-day blocks before today; levels are
     * [weekLevel]s (BUG-21). Acute overload: last week above [DOWN_ACUTE_RATIO] × the heaviest level
     * of the last [DOWN_ACUTE_LOOKBACK_WEEKS] weeks (training at the `BUILD` target sits near 1.3, so
     * this is clearly beyond the plan, and a normal week after a break is not "acute"). Big build:
     * the last three weeks average above [DOWN_BUILD_RATIO] × the usual week. Fatigue: at least
     * [DOWN_FATIGUED_DAYS] of the last seven days `FATIGUED`/`STRAINED` (one bad night is the daily
     * multiplier's job). Long build: [DOWN_LONG_BUILD_WEEKS] weeks in a row none of which was lighter
     * than [DOWN_LIGHT_RATIO] of its own level — a holiday, a sick week or an accepted down week
     * counts.
     */
    const val DOWN_ACUTE_RATIO: Double = 1.5
    const val DOWN_ACUTE_LOOKBACK_WEEKS: Int = 6
    const val DOWN_BUILD_RATIO: Double = 1.4
    const val DOWN_BUILD_WEEKS: Int = 3
    const val DOWN_FATIGUED_DAYS: Int = 3
    const val DOWN_LONG_BUILD_WEEKS: Int = 5
    const val DOWN_LIGHT_RATIO: Double = 0.8

    /**
     * The weekly target may never exceed last week's actual load by more than 25 % — except that
     * the usual week is always allowed (BUG-21): returning to it is not a ramp.
     */
    const val MAX_RAMP_FACTOR: Double = 1.25

    /** …but a returning athlete is always allowed at least this much (the ramp cap's floor). */
    const val RAMP_FLOOR_AU: Double = 150.0

    /** POLISH-10: below this CTL there is not enough history for `ctl * 7 * factor` to mean anything. */
    const val STARTER_CTL_THRESHOLD: Double = 5.0

    /** POLISH-10: a brand-new athlete (CTL < 5, no load logged last week) gets this instead of ~0 AU. */
    const val STARTER_TARGET_AU: Double = 150.0

    const val ACWR_SUPPRESS_ABOVE: Double = 1.5
    const val ACWR_SUPPRESS_FACTOR: Double = 0.75
    const val FATIGUED_FACTOR: Double = 0.85
    const val STRAINED_FACTOR: Double = 0.60

    const val DAYS_PER_WEEK: Int = 7

    /** Phase → weekly-load factor applied to `ctl * 7` (§3.5.2). */
    fun factorFor(phase: TrainingPhase): Double = when (phase) {
        TrainingPhase.BASE -> 1.20
        TrainingPhase.BUILD -> 1.30
        TrainingPhase.PEAK -> 1.15
        TrainingPhase.TAPER -> 0.60
        TrainingPhase.RACE_WEEK -> 0.45
        TrainingPhase.IN_SEASON -> 1.00
        TrainingPhase.OFF_SEASON -> 0.80
        TrainingPhase.RECOVERY_WEEK -> 0.65
    }

    /** The goal types the phase table periodizes towards: a running race or a cycling event. */
    val RACE_GOAL_TYPES: Set<GoalType> = setOf(GoalType.RACE_TIME, GoalType.BIKE_EVENT)

    /**
     * The primary race goal: a [RACE_GOAL_TYPES] goal with a `targetDay` that has not passed,
     * lowest priority. P19: only a goal marked as a race (`isRace`) — a deadline never tapers.
     * P12.3 adds `BIKE_EVENT` — a cycling event periodizes exactly like a race,
     * only the preferred sessions of each phase differ ([bikePreferredTypes]).
     */
    fun primaryRaceGoal(goals: List<Goal>, todayDay: Long): Goal? = goals
        .filter { it.type in RACE_GOAL_TYPES && it.isRace && (it.targetDay ?: Long.MIN_VALUE) >= todayDay }
        .minByOrNull { it.priority }

    /**
     * The second phase table of P12.3, used instead of [Scorer.preferredTypes] when the primary
     * goal's sport group is `CYCLE`. Same phases, bike sessions: the strength rows stay because a
     * cyclist still needs them, and `MOBILITY` fills the phases that are about recovering.
     */
    fun bikePreferredTypes(phase: TrainingPhase): Set<SessionType> = when (phase) {
        TrainingPhase.BASE ->
            setOf(SessionType.ENDURANCE_RIDE, SessionType.TRAINER_SESSION, SessionType.STRENGTH_FULL)
        TrainingPhase.BUILD ->
            setOf(SessionType.BIKE_INTERVALS, SessionType.ENDURANCE_RIDE, SessionType.STRENGTH_LOWER)
        TrainingPhase.PEAK -> setOf(SessionType.BIKE_INTERVALS, SessionType.ENDURANCE_RIDE)
        TrainingPhase.TAPER -> setOf(SessionType.RECOVERY_SPIN, SessionType.BIKE_INTERVALS)
        TrainingPhase.RACE_WEEK -> setOf(SessionType.RECOVERY_SPIN, SessionType.MOBILITY)
        TrainingPhase.IN_SEASON ->
            setOf(SessionType.ENDURANCE_RIDE, SessionType.STRENGTH_UPPER, SessionType.MOBILITY)
        TrainingPhase.RECOVERY_WEEK ->
            setOf(SessionType.RECOVERY_SPIN, SessionType.ENDURANCE_RIDE, SessionType.MOBILITY)
        TrainingPhase.OFF_SEASON ->
            setOf(SessionType.TRAINER_SESSION, SessionType.STRENGTH_FULL, SessionType.MOBILITY)
    }

    /** Days from today to the primary race, or `null` when there is none to periodize towards. */
    fun daysToRace(goals: List<Goal>, todayDay: Long): Long? =
        primaryRaceGoal(goals, todayDay)?.targetDay?.minus(todayDay)

    /** True when a `SOCCER_MATCH` occurs in `[today, today + 21]` (§3.5.2's `IN_SEASON` rule). */
    fun matchWithinWindow(
        events: List<EventOccurrence>,
        todayDay: Long,
        windowDays: Long = IN_SEASON_MATCH_WINDOW_DAYS,
    ): Boolean = events.any {
        it.type == EventType.SOCCER_MATCH &&
            it.occurrenceDay >= todayDay &&
            it.occurrenceDay <= todayDay + windowDays
    }

    /**
     * BUG-21: the load level of the seven days from [firstDay] — `7 ×` the mean CTL of the rows in
     * `[firstDay, firstDay + 6]` — or `null` when none of those days has a row. Averaging is what
     * makes the level independent of the weekday it is read on.
     */
    fun weekLevel(byDay: Map<Long, DailyLoad>, firstDay: Long): Double? {
        val ctls = (firstDay until firstDay + DAYS_PER_WEEK).mapNotNull { byDay[it]?.ctl }
        return if (ctls.isEmpty()) null else ctls.average() * DAYS_PER_WEEK
    }

    /** BUG-21: the usual week going into the coming one — the [weekLevel] ending at the newest row. */
    fun usualWeek(recentLoad: List<DailyLoad>): Double? {
        val latest = latestLoad(recentLoad) ?: return null
        return weekLevel(recentLoad.associateBy { it.day }, latest.day - DAYS_PER_WEEK + 1)
    }

    /**
     * BUG-21: where the newest down week began, from the horizon starts of the accepted
     * `RECOVERY_WEEK` batches. A batch regenerated while a down week runs starts inside it: it
     * belongs to that week and must not push the week's end back.
     */
    fun downWeekStart(acceptedStarts: List<Long>): Long? {
        val newestFirst = acceptedStarts.sortedDescending()
        var start = newestFirst.firstOrNull() ?: return null
        for (earlier in newestFirst.drop(1)) {
            if (start - earlier < DAYS_PER_WEEK) start = earlier else break
        }
        return start
    }

    /**
     * P19.6: why the coming week should be a down week, or `null` when nothing calls for one.
     * [recentLoad] is the last 42 days of `daily_load`; [ctl] today's CTL. A starter athlete
     * (CTL below [STARTER_CTL_THRESHOLD]) never gets one — there is nothing to absorb yet.
     *
     * BUG-21: [lastDownWeekStartDay] is where the newest accepted down week started. While it runs
     * the week stays a down week ([DownWeekReason.CONTINUED], so regenerating mid-week does not undo
     * it); afterwards it is the lighter week the big-build and long-build rules look for, whether or
     * not the load actually dropped — fixed team training may not move, and asking again every week
     * only turns every week into a recovery week. Acute overload and fatigue stay reactive.
     */
    fun downWeekReason(
        recentLoad: List<DailyLoad>,
        todayDay: Long,
        ctl: Double,
        lastDownWeekStartDay: Long? = null,
    ): DownWeekReason? {
        if (ctl < STARTER_CTL_THRESHOLD) return null
        val sinceDownWeek = lastDownWeekStartDay?.let { todayDay - it }?.takeIf { it >= 0 }
        if (sinceDownWeek != null && sinceDownWeek < DAYS_PER_WEEK) return DownWeekReason.CONTINUED
        // A planned down week overlaps the last `weeks` weeks before today.
        fun downWeekWithin(weeks: Int): Boolean = sinceDownWeek != null && sinceDownWeek < DAYS_PER_WEEK * (weeks + 1)

        val byDay = recentLoad.associateBy { it.day }
        fun weekStart(weeksBack: Int): Long = todayDay - DAYS_PER_WEEK * weeksBack
        fun weekSum(weeksBack: Int): Double {
            val start = weekStart(weeksBack)
            return (start until start + DAYS_PER_WEEK).sumOf { byDay[it]?.trimp ?: 0.0 }
        }

        val heaviest = (1..DOWN_ACUTE_LOOKBACK_WEEKS).mapNotNull { weekLevel(byDay, weekStart(it)) }.maxOrNull()
        if (heaviest != null && weekSum(1) > heaviest * DOWN_ACUTE_RATIO) return DownWeekReason.ACUTE_OVERLOAD
        val lastSeven = (todayDay - DAYS_PER_WEEK until todayDay).mapNotNull { byDay[it]?.recoveryBand }
        if (lastSeven.count { it == RecoveryBand.FATIGUED || it == RecoveryBand.STRAINED } >= DOWN_FATIGUED_DAYS) {
            return DownWeekReason.FATIGUE
        }
        val usual = usualWeek(recentLoad) ?: return null
        val buildAverage = (1..DOWN_BUILD_WEEKS).sumOf { weekSum(it) } / DOWN_BUILD_WEEKS
        if (!downWeekWithin(DOWN_BUILD_WEEKS) && buildAverage > usual * DOWN_BUILD_RATIO) {
            return DownWeekReason.BIG_BUILD
        }

        if (downWeekWithin(DOWN_LONG_BUILD_WEEKS)) return null
        val longBuild = (1..DOWN_LONG_BUILD_WEEKS).all { weeksBack ->
            // A week with no history before it is not "build-up" — new users never trip this.
            if (byDay[weekStart(weeksBack)] == null) return@all false
            val level = weekLevel(byDay, weekStart(weeksBack)) ?: return@all false
            weekSum(weeksBack) >= level * DOWN_LIGHT_RATIO
        }
        return if (longBuild) DownWeekReason.LONG_BUILD else null
    }

    /**
     * The §3.5.2 phase table plus the P19.6 down week ([downWeek], from [downWeekReason]), which
     * never overrides a `TAPER` or `RACE_WEEK` — the race outranks it.
     */
    fun phase(daysToRace: Long?, matchWithin21Days: Boolean, downWeek: Boolean = false): TrainingPhase {
        val base = when {
            daysToRace == null && matchWithin21Days -> TrainingPhase.IN_SEASON
            daysToRace == null -> TrainingPhase.BASE
            daysToRace <= RACE_WEEK_MAX_DAYS -> TrainingPhase.RACE_WEEK
            daysToRace <= TAPER_MAX_DAYS -> TrainingPhase.TAPER
            daysToRace <= PEAK_MAX_DAYS -> TrainingPhase.PEAK
            daysToRace <= BUILD_MAX_DAYS -> TrainingPhase.BUILD
            else -> TrainingPhase.BASE
        }
        val protected = base == TrainingPhase.TAPER || base == TrainingPhase.RACE_WEEK
        return if (downWeek && !protected) TrainingPhase.RECOVERY_WEEK else base
    }

    /**
     * POLISH-10: true for a brand-new athlete — CTL hasn't built up yet **and** nothing was logged
     * last week either, so `ctl * 7 * factor` collapses to ~0 and the ramp cap's `max(_, 150)` floor
     * never kicks in (it only guards the *cap*, and the raw target is already below it). A returning
     * athlete who simply rested last week has `ctl >= 5` and hits [RAMP_FLOOR_AU] instead — this flag
     * is specifically "no history to plan from", not "no load last week".
     */
    fun isStarterWeek(ctl: Double, lastWeekActual: Double): Boolean =
        ctl < STARTER_CTL_THRESHOLD && lastWeekActual <= 0.0

    /**
     * The weekly AU budget of §3.5.2, in the order the plan writes it: phase factor, then the
     * 25 % ramp cap (never below the usual week, floored at 150 AU) — or the POLISH-10 starter
     * target when there is no history at all — then the ACWR and recovery multipliers, then
     * `max(_, 0)`. [usualWeek] is BUG-21's averaged level; it defaults to `7 × ctl` for callers that
     * only have one day's CTL.
     */
    fun weeklyTarget(
        phase: TrainingPhase,
        ctl: Double,
        lastWeekActual: Double,
        acwr: Double?,
        band: RecoveryBand?,
        usualWeek: Double = ctl * DAYS_PER_WEEK,
    ): Double {
        var target = if (isStarterWeek(ctl, lastWeekActual)) {
            STARTER_TARGET_AU
        } else {
            val base = usualWeek * factorFor(phase)
            min(base, maxOf(lastWeekActual * MAX_RAMP_FACTOR, usualWeek, RAMP_FLOOR_AU))
        }
        if (acwr != null && acwr > ACWR_SUPPRESS_ABOVE) target *= ACWR_SUPPRESS_FACTOR
        if (band == RecoveryBand.FATIGUED) target *= FATIGUED_FACTOR
        if (band == RecoveryBand.STRAINED) target *= STRAINED_FACTOR
        return max(target, 0.0)
    }

    /** Sum of `trimp` over the seven days before [todayDay]. */
    fun lastWeekActual(recentLoad: List<DailyLoad>, todayDay: Long): Double = recentLoad
        .filter { it.day in (todayDay - DAYS_PER_WEEK) until todayDay }
        .sumOf { it.trimp }

    /** The most recent `daily_load` row, by day — §3.5.2's `recentLoad.last()`. */
    fun latestLoad(recentLoad: List<DailyLoad>): DailyLoad? = recentLoad.maxByOrNull { it.day }

    /** Phase + budget for one [SuggestionInput]; step 3 of the §3.5.6 algorithm. */
    fun compute(input: SuggestionInput): PeriodizationResult {
        val todayDay = input.todayDay
        val latest = latestLoad(input.recentLoad)
        val ctl = latest?.ctl ?: 0.0
        val acwr = latest?.acwr
        val lastWeek = lastWeekActual(input.recentLoad, todayDay)
        val usual = usualWeek(input.recentLoad) ?: (ctl * DAYS_PER_WEEK)
        val daysToRace = daysToRace(input.goals, todayDay)
        val reason = downWeekReason(input.recentLoad, todayDay, ctl, input.lastDownWeekStartDay)
        val phase = phase(
            daysToRace = daysToRace,
            matchWithin21Days = matchWithinWindow(input.events, todayDay),
            downWeek = reason != null,
        )
        val cycleFactor = CycleRules.weeklyTargetFactor(input.cycleStatusByDay, input.horizonDays)
        return PeriodizationResult(
            phase = phase,
            weeklyTarget = weeklyTarget(phase, ctl, lastWeek, acwr, input.recovery.bandOf(), usual) * cycleFactor,
            daysToRace = daysToRace,
            ctl = ctl,
            lastWeekActual = lastWeek,
            acwr = acwr,
            band = input.recovery.bandOf(),
            isStarterWeek = isStarterWeek(ctl, lastWeek),
            downWeekReason = reason.takeIf { phase == TrainingPhase.RECOVERY_WEEK },
        )
    }

    private fun RecoveryState?.bandOf(): RecoveryBand? = this?.band
}

/** [Periodization.compute]'s output — the budget plus every input that shaped it (for rationale). */
data class PeriodizationResult(
    val phase: TrainingPhase,
    val weeklyTarget: Double,
    val daysToRace: Long?,
    val ctl: Double,
    val lastWeekActual: Double,
    val acwr: Double?,
    val band: RecoveryBand?,
    /** POLISH-10: true when this is a brand-new athlete's first generated week. */
    val isStarterWeek: Boolean = false,
    /** P19.6: why this is a `RECOVERY_WEEK`; `null` in every other phase. */
    val downWeekReason: DownWeekReason? = null,
)

/**
 * P19.6: the rule of [Periodization.downWeekReason] that made the coming week a down week.
 * BUG-21 adds [CONTINUED]: the accepted down week is still running.
 */
enum class DownWeekReason { ACUTE_OVERLOAD, FATIGUE, BIG_BUILD, LONG_BUILD, CONTINUED }

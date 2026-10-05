package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.TrainingPhase

/**
 * Post-pass 7c (0.3.0, owner request): **active recovery on rest days**.
 *
 * The greedy loop (steps 4–6) spends the weekly budget on real sessions and leaves the rest days
 * empty; 7d then puts mobility on them. Recovery runs and spins existed as catalog rows but, as
 * ordinary candidates, they competed with the long run and strength for the same budget and lost —
 * so an athlete with a run cap never saw an easy run on a recovery day. This pass treats them like
 * mobility instead: fillers that keep the day a rest day (C3), cost no budget and no cap.
 *
 * Rules, in order:
 * 1. Only rest days that are not blocked, not the eve of a match or race, and not a `STRAINED`
 *    today (C8) take a filler; those stay mobility-only.
 * 2. **One true rest day per horizon** keeps nothing but mobility: the rest day farthest after the
 *    most recent hard day (a `HIGH`/`MAX` session, a key event, or a completed day with ≥ 200 AU);
 *    ties go to the later day. With a single rest day nothing is placed.
 * 3. Sport: `RECOVERY_RUN` when the run cap allows running (absent cap = unlimited),
 *    `RECOVERY_SPIN` when the cycle cap is above zero or an indoor trainer is available. Neither →
 *    the pass does nothing (pre-0.3.0 behaviour, so `sug28`'s bike-free guard still holds for
 *    profiles without a run).
 * 4. Alternation: consecutive fillers switch sport when both are available; a filler is skipped
 *    (mobility only) when the same session type already sits on an adjacent day (C13's spirit).
 * 5. Cycle (P11.2): on menstrual days 1–2 and in the late-luteal window a spin is preferred over a
 *    run when both are available.
 *
 * Fillers carry [SuggestionEngine.MOBILITY_SCORE] and `isActiveRecovery = true`; step 7d still adds
 * mobility on top when the profile asks for it.
 */
object ActiveRecovery {

    /** The filler's default duration comes from the catalog row (30 min for both types). */
    internal fun apply(
        grid: SuggestionGrid,
        ctx: ConstraintContext,
        bike: BikeContext,
        phase: TrainingPhase,
        cycleStatusByDay: Map<Long, CycleStatus>,
        isStarterWeek: Boolean,
    ): SuggestionGrid {
        val runAllowed = (ctx.weeklyCaps[SportGroup.RUN] ?: Int.MAX_VALUE) > 0
        val spinAllowed = (ctx.weeklyCaps[SportGroup.CYCLE] ?: 0) > 0 || bike.trainerAvailable
        if (!runAllowed && !spinAllowed) return grid

        val restDays = grid.days
            .filter { it.isRestDay && !it.isBlocked && !isEveOfKeyEvent(it.day, ctx) && !isStrainedToday(it.day, ctx) }
            .map { it.day }
        if (restDays.size < 2) return grid

        val hardDays = hardDays(grid, ctx)
        val trueRestDay = restDays.maxWith(compareBy({ daysSinceHard(it, hardDays) }, { it }))

        var current = grid
        var lastFiller: SessionType? = null
        for (day in restDays) {
            if (day == trueRestDay) { lastFiller = null; continue }
            val type = choose(day, runAllowed, spinAllowed, lastFiller, cycleStatusByDay[day])
            if (type == null || adjacentHasSameType(current, day, type)) { lastFiller = null; continue }
            val entry = SessionCatalog.entryFor(type) ?: continue
            val cycleDriven = type == SessionType.RECOVERY_SPIN && runAllowed &&
                prefersSpin(cycleStatusByDay[day])
            current = current.place(
                day,
                Candidate(entry, day).asPlacedItem(
                    score = SuggestionEngine.MOBILITY_SCORE,
                    rationale = Rationale.forActiveRecovery(
                        sessionType = type,
                        phase = phase,
                        afterHardDay = daysSinceHard(day, hardDays) == 1L,
                        cycleDriven = cycleDriven,
                        isStarterWeek = isStarterWeek,
                    ),
                ).copy(isActiveRecovery = true),
            )
            lastFiller = type
        }
        return current
    }

    /** Which filler a day gets, or null when nothing fits. */
    internal fun choose(
        day: Long,
        runAllowed: Boolean,
        spinAllowed: Boolean,
        lastFiller: SessionType?,
        cycle: CycleStatus?,
    ): SessionType? = when {
        runAllowed && spinAllowed -> when {
            prefersSpin(cycle) -> SessionType.RECOVERY_SPIN
            lastFiller == SessionType.RECOVERY_RUN -> SessionType.RECOVERY_SPIN
            lastFiller == SessionType.RECOVERY_SPIN -> SessionType.RECOVERY_RUN
            day % 2 == 0L -> SessionType.RECOVERY_RUN
            else -> SessionType.RECOVERY_SPIN
        }
        runAllowed -> SessionType.RECOVERY_RUN
        spinAllowed -> SessionType.RECOVERY_SPIN
        else -> null
    }

    /** P11.2's gentle days: menstrual days 1–2 and the late-luteal window. */
    internal fun prefersSpin(cycle: CycleStatus?): Boolean {
        if (cycle == null) return false
        return cycle.isEarlyMenstrual || cycle.isLateLuteal
    }

    private fun isEveOfKeyEvent(day: Long, ctx: ConstraintContext): Boolean = (day + 1) in ctx.matchOrRaceDays

    /** C8: a `STRAINED` recovery makes today a real rest day — no filler either. */
    private fun isStrainedToday(day: Long, ctx: ConstraintContext): Boolean =
        day == ctx.todayDay && ctx.recoveryBand == RecoveryBand.STRAINED

    /** Days that end in real fatigue: a hard or key-event item on the grid, or a completed ≥ 200 AU day. */
    private fun hardDays(grid: SuggestionGrid, ctx: ConstraintContext): Set<Long> {
        val onGrid = grid.days
            .filter { plan -> plan.sessions.any { it.isHard || it.isKeyEvent } }
            .map { it.day }
        return onGrid.toSet() + ctx.highLoadDays + ctx.matchOrRaceDays
    }

    /** Days since the most recent hard day strictly before [day]; `Long.MAX_VALUE` when none. */
    private fun daysSinceHard(day: Long, hardDays: Set<Long>): Long =
        hardDays.filter { it < day }.maxOrNull()?.let { day - it } ?: Long.MAX_VALUE

    private fun adjacentHasSameType(grid: SuggestionGrid, day: Long, type: SessionType): Boolean =
        grid.entries().any { (otherDay, item) ->
            otherDay != day && kotlin.math.abs(otherDay - day) == 1L && item.sessionType == type
        }
}

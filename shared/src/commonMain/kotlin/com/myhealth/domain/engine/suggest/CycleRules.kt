package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup

/**
 * The cycle-aware half of the suggester (PLAN §5 P11.2). Four rules, each with the rationale id the
 * plan names, applied to **suggested** sessions only — a match, a race or a locked planned session
 * on the calendar is never moved, capped or rescored by any of them.
 *
 * | Phase | Rule | Effect |
 * |---|---|---|
 * | cycle days 1–2 | `CYCLE_MENSTRUAL_EARLY` | intensity capped at `MODERATE` |
 * | days 3 … period end | `CYCLE_MENSTRUAL_EARLY` | `HIGH` allowed, `recoveryFit × 0.85` for `HIGH`/`MAX` |
 * | follicular | `CYCLE_FOLLICULAR` | `+0.10` score for hard runs and any `STRENGTH_*` |
 * | ovulation window | `CYCLE_OVULATION` | `MAX` capped to `HIGH`; rationale asks for a long warm-up |
 * | last 5 days | `CYCLE_LATE_LUTEAL` | ≤ 1 hard session in the window, `+0.10` for easy work, weekly target × 0.90 over those days |
 *
 * The rules fire at every [CycleConfidence]; a [CycleConfidence.LOW] forecast only changes what the
 * rationale says (see [Rationale]), because a default 28-day cycle is still a better guess than no
 * cycle at all — and the caller can always switch the tracker off.
 *
 * Ambiguity note: §P11.2's "weekly target ×0.90 for the days in the window" scales a *weekly*
 * number by a property of *individual days*, so it is applied pro-rata —
 * [weeklyTargetFactor] removes 10 % in proportion to how much of the horizon is late luteal. A
 * horizon entirely inside the window is therefore exactly ×0.90 (test `sug24`), and a horizon that
 * merely clips it loses proportionally less.
 */
object CycleRules {

    const val RULE_MENSTRUAL_EARLY: String = "CYCLE_MENSTRUAL_EARLY"
    const val RULE_FOLLICULAR: String = "CYCLE_FOLLICULAR"
    const val RULE_OVULATION: String = "CYCLE_OVULATION"
    const val RULE_LATE_LUTEAL: String = "CYCLE_LATE_LUTEAL"

    /** Cycle days 1–2 take nothing above this. */
    val EARLY_MENSTRUAL_MAX_INTENSITY: Intensity = Intensity.MODERATE

    /** The ovulation window takes nothing above this (ligament laxity). */
    val OVULATION_MAX_INTENSITY: Intensity = Intensity.HIGH

    /** Days 3 … period end: hard work is allowed but scores as if recovery were 15 % worse. */
    const val MENSTRUAL_HARD_RECOVERY_FACTOR: Double = 0.85

    /** The score nudge a phase gives the work it suits. */
    const val PHASE_SCORE_BONUS: Double = 0.10

    /** At most this many hard sessions across the whole late-luteal window. */
    const val LATE_LUTEAL_MAX_HARD: Int = 1

    /** The weekly load target over late-luteal days. */
    const val LATE_LUTEAL_TARGET_FACTOR: Double = 0.90

    /** Intensities the late-luteal bonus rewards, alongside `MOBILITY`. */
    val EASY_INTENSITIES: Set<Intensity> = setOf(Intensity.RECOVERY, Intensity.LOW)

    fun isHard(intensity: Intensity): Boolean =
        intensity == Intensity.HIGH || intensity == Intensity.MAX

    /** True when the candidate's intensity exceeds what [status]'s phase allows. */
    fun violatesEarlyMenstrualCap(status: CycleStatus?, intensity: Intensity): Boolean {
        val cycle = status ?: return false
        return cycle.isEarlyMenstrual && intensity.ordinal > EARLY_MENSTRUAL_MAX_INTENSITY.ordinal
    }

    /** §P11.2: in the ovulation window a `MAX` candidate is capped down to `HIGH`. */
    fun violatesOvulationCap(status: CycleStatus?, intensity: Intensity): Boolean {
        val cycle = status ?: return false
        return cycle.phase == CyclePhase.OVULATION && intensity.ordinal > OVULATION_MAX_INTENSITY.ordinal
    }

    /** `recoveryFit` multiplier: 0.85 for hard work on a menstrual day that is not capped outright. */
    fun recoveryFactor(status: CycleStatus?, intensity: Intensity): Double {
        val cycle = status ?: return 1.0
        return if (cycle.isMenstrual && !cycle.isEarlyMenstrual && isHard(intensity)) {
            MENSTRUAL_HARD_RECOVERY_FACTOR
        } else {
            1.0
        }
    }

    /** The `+0.10` of the follicular and late-luteal rules; `0.0` on every other day. */
    fun scoreBonus(status: CycleStatus?, sessionType: SessionType, sportGroup: SportGroup, intensity: Intensity): Double {
        val cycle = status ?: return 0.0
        val follicular = cycle.phase == CyclePhase.FOLLICULAR &&
            ((sportGroup == SportGroup.RUN && isHard(intensity)) || sportGroup == SportGroup.STRENGTH)
        val lateLuteal = cycle.isLateLuteal &&
            (intensity in EASY_INTENSITIES || sessionType == SessionType.MOBILITY)
        return if (follicular || lateLuteal) PHASE_SCORE_BONUS else 0.0
    }

    /** Which of the horizon's days are late luteal — the window the last rule applies to. */
    fun lateLutealDays(cycleStatusByDay: Map<Long, CycleStatus>): Set<Long> =
        cycleStatusByDay.filterValues { it.isLateLuteal }.keys

    /**
     * The pro-rata ×0.90 of the last rule: `1.0` with no late-luteal day in the horizon, `0.90`
     * when every day of it is late luteal, linear in between.
     */
    fun weeklyTargetFactor(cycleStatusByDay: Map<Long, CycleStatus>, horizonDays: Int): Double {
        if (horizonDays <= 0 || cycleStatusByDay.isEmpty()) return 1.0
        val share = lateLutealDays(cycleStatusByDay).size.toDouble() / horizonDays
        return 1.0 - (1.0 - LATE_LUTEAL_TARGET_FACTOR) * share.coerceIn(0.0, 1.0)
    }
}

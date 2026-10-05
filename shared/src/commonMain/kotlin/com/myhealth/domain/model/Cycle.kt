package com.myhealth.domain.model

/**
 * Menstrual-cycle tracking (PLAN §5 P11.1). The observable anchor is the **first day of the
 * period**; ovulation and every phase boundary are forecast from it, never entered by hand.
 */

/**
 * One logged period, the single row type of the `cycle_entry` table. [periodStartDay] is unique:
 * a period starts on exactly one day, and re-logging the same day edits that entry.
 *
 * [periodEndDay] is `null` while the period is still running (the user has not tapped
 * "Period ended" yet); it is inclusive, so a period of days 100..104 is five days long.
 */
data class CycleEntry(
    val id: Long = 0L,
    val periodStartDay: Long,
    val periodEndDay: Long? = null,
    val note: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    /** Inclusive length in days of a *finished* period, or `null` while it is still running. */
    val periodLengthDays: Int?
        get() = periodEndDay
            ?.takeIf { it >= periodStartDay }
            ?.let { (it - periodStartDay + 1).toInt() }
}

/** The four phases P11.1 models. `isLateLuteal` is carried by [CycleStatus], not by the phase. */
enum class CyclePhase { MENSTRUAL, FOLLICULAR, OVULATION, LUTEAL }

/**
 * How much the forecast can be trusted (P11.1): [HIGH] = at least three logged intervals whose
 * population SD is within three days, [MEDIUM] = at least one interval, [LOW] = the 28/5 defaults.
 */
enum class CycleConfidence { LOW, MEDIUM, HIGH }

/**
 * Where one calendar day sits in the cycle.
 *
 * Documented extension of the P11.1 field list: [day] (the epoch day this status describes) is
 * carried along so a status can be put in a map, shown on a card or attached to a suggestion
 * without the caller having to remember which date it asked about.
 */
data class CycleStatus(
    val day: Long,
    /** 1-based day of the cycle; always in `1..cycleLengthDays`. */
    val dayOfCycle: Int,
    val phase: CyclePhase,
    /** The last five days before [nextPeriodStart] — the phase the training rules care about. */
    val isLateLuteal: Boolean,
    /** True when [day] lies beyond the last logged cycle and the engine rolled the cycle forward. */
    val isPredicted: Boolean,
    val cycleLengthDays: Int,
    val periodLengthDays: Int,
    val nextPeriodStart: Long,
    /** Absolute epoch day of the forecast ovulation: `nextPeriodStart - 14` (fixed luteal phase). */
    val ovulationDay: Long,
    /** `ovulationDay - 5 .. ovulationDay + 1`. */
    val fertileWindow: ClosedRange<Long>,
    val confidence: CycleConfidence,
) {
    /** Days from [day] to [nextPeriodStart]; 0 on the predicted start itself. */
    val daysToNextPeriod: Long get() = nextPeriodStart - day

    val isMenstrual: Boolean get() = phase == CyclePhase.MENSTRUAL

    /** Cycle days 1–2 — the only days the suggester caps at `MODERATE`. */
    val isEarlyMenstrual: Boolean get() = isMenstrual && dayOfCycle <= EARLY_MENSTRUAL_LAST_DAY

    val isFertile: Boolean get() = day in fertileWindow

    companion object {
        const val EARLY_MENSTRUAL_LAST_DAY: Int = 2
    }
}

/** One forecast cycle: when the period is expected, and when ovulation falls inside it. */
data class PredictedCycle(
    val periodStart: Long,
    /** Inclusive last predicted period day. */
    val periodEnd: Long,
    val ovulationDay: Long,
    val fertileWindow: ClosedRange<Long>,
)

/** The next N predicted cycles, ascending by [PredictedCycle.periodStart]. */
data class CycleForecast(
    val cycles: List<PredictedCycle> = emptyList(),
    val cycleLengthDays: Int = CycleDefaults.CYCLE_LENGTH_DAYS,
    val periodLengthDays: Int = CycleDefaults.PERIOD_LENGTH_DAYS,
    val confidence: CycleConfidence = CycleConfidence.LOW,
) {
    val isEmpty: Boolean get() = cycles.isEmpty()
}

/** The constants P11.1 fixes; kept in `model` so UI and engine read the same numbers. */
object CycleDefaults {
    /** Used until at least one interval between two logged period starts exists. */
    const val CYCLE_LENGTH_DAYS: Int = 28
    const val PERIOD_LENGTH_DAYS: Int = 5

    const val MIN_CYCLE_LENGTH_DAYS: Int = 21
    const val MAX_CYCLE_LENGTH_DAYS: Int = 45
    const val MIN_PERIOD_LENGTH_DAYS: Int = 2
    const val MAX_PERIOD_LENGTH_DAYS: Int = 10

    /** The luteal phase is treated as fixed: ovulation is this many days before the next start. */
    const val LUTEAL_PHASE_DAYS: Int = 14

    /** Fertile window: ovulation − 5 … ovulation + 1. */
    const val FERTILE_DAYS_BEFORE: Int = 5
    const val FERTILE_DAYS_AFTER: Int = 1

    /** "Late luteal" = the last five days before the predicted next period start. */
    const val LATE_LUTEAL_DAYS: Int = 5

    /** At most this many past cycles feed the averages. */
    const val AVERAGING_WINDOW_CYCLES: Int = 6

    /** [CycleConfidence.HIGH] needs this many intervals and at most this much population SD. */
    const val HIGH_CONFIDENCE_MIN_INTERVALS: Int = 3
    const val HIGH_CONFIDENCE_MAX_SD_DAYS: Double = 3.0

    /** How many cycles `CycleEngine.forecast` produces by default. */
    const val FORECAST_CYCLES: Int = 6
}

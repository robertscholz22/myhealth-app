package com.myhealth.domain.engine.cycle

import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CycleDefaults as D
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CycleForecast
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.PredictedCycle
import kotlin.math.floor
import kotlin.math.sqrt

/** The averages the whole engine hangs off, plus how much they can be trusted (PLAN P11.1). */
data class CycleAverages(
    val cycleLengthDays: Int,
    val periodLengthDays: Int,
    val confidence: CycleConfidence,
    /** Intervals actually used, after the 21–45 clamp; empty when only defaults were available. */
    val intervals: List<Int> = emptyList(),
)

/**
 * The menstrual-cycle engine of PLAN §5 P11.1 — pure Kotlin, no clock, no Android.
 *
 * Everything is derived from one observable fact, the **first day of the period**:
 *
 * - cycle length = mean of the intervals between consecutive logged period starts, over the last
 *   ≤ 6 cycles, clamped to 21–45 days (28 when there is not a single interval yet);
 * - period length = mean of `end − start + 1` over the last ≤ 6 *finished* periods, clamped to
 *   2–10 days (5 when none is finished);
 * - the luteal phase is treated as fixed at 14 days, so ovulation is `nextPeriodStart − 14` and
 *   the fertile window is `ovulation − 5 … ovulation + 1`.
 *
 * Ambiguity notes (P11.1 leaves these open; each choice is pinned by a named test):
 * - The spec gives ovulation twice: as the absolute date `nextPeriodStart − 14` (`cyc07`) and as
 *   the day-of-cycle index `cycleLength − 14` that the `OVULATION` phase window is built from
 *   (`cyc05`: days 13–15 of a 28-day cycle). Those differ by one day, because a cycle day is
 *   1-based while the countdown to the next start is not. Both readings are implemented exactly as
 *   written: [ovulationDayOf] returns `nextPeriodStart − 14` and [phaseOf] uses
 *   `cycleLength − 14` as the centre index. The absolute ovulation date therefore always falls
 *   inside the three-day `OVULATION` window (at its top edge), which is what both tests assert.
 * - "clamped 21–45" is applied to **every interval before averaging** as well as to the mean, so a
 *   single missed log (a 56-day gap) cannot drag the forecast; with in-range data the two readings
 *   are identical.
 * - The next period start is always `effectiveCycleStart + cycleLength`, never a later *logged*
 *   start: the phase boundaries are derived from `cycleLength`, and mixing the two sources would
 *   let the late-luteal window disagree with the phase table.
 * - A day before the very first logged start has no cycle to belong to, so [statusFor] returns
 *   `null` there, exactly as it does when nothing is logged at all (`cyc11`).
 */
object CycleEngine {

    // ---- averages ---------------------------------------------------------------------------

    /** Logged starts, ascending and de-duplicated (`periodStartDay` is unique in the schema). */
    private fun starts(entries: List<CycleEntry>): List<Long> =
        entries.map { it.periodStartDay }.distinct().sorted()

    /** Gaps between consecutive starts, newest ≤ 6 of them, each clamped to 21–45 days. */
    fun intervals(entries: List<CycleEntry>): List<Int> {
        val days = starts(entries)
        if (days.size < 2) return emptyList()
        return days.zipWithNext { a, b -> (b - a).toInt() }
            .takeLast(D.AVERAGING_WINDOW_CYCLES)
            .map { it.coerceIn(D.MIN_CYCLE_LENGTH_DAYS, D.MAX_CYCLE_LENGTH_DAYS) }
    }

    /** Lengths of the newest ≤ 6 finished periods, each clamped to 2–10 days. */
    fun periodLengths(entries: List<CycleEntry>): List<Int> = entries
        .sortedBy { it.periodStartDay }
        .mapNotNull { it.periodLengthDays }
        .takeLast(D.AVERAGING_WINDOW_CYCLES)
        .map { it.coerceIn(D.MIN_PERIOD_LENGTH_DAYS, D.MAX_PERIOD_LENGTH_DAYS) }

    /** Cycle length, period length and confidence for one history. */
    fun averages(entries: List<CycleEntry>): CycleAverages {
        val gaps = intervals(entries)
        val lengths = periodLengths(entries)
        val cycleLength = if (gaps.isEmpty()) {
            D.CYCLE_LENGTH_DAYS
        } else {
            roundHalfUp(gaps.average()).coerceIn(D.MIN_CYCLE_LENGTH_DAYS, D.MAX_CYCLE_LENGTH_DAYS)
        }
        val periodLength = if (lengths.isEmpty()) {
            D.PERIOD_LENGTH_DAYS
        } else {
            roundHalfUp(lengths.average()).coerceIn(D.MIN_PERIOD_LENGTH_DAYS, D.MAX_PERIOD_LENGTH_DAYS)
        }
        return CycleAverages(
            cycleLengthDays = cycleLength,
            // A period can never be longer than the cycle it opens.
            periodLengthDays = minOf(periodLength, cycleLength - 1),
            confidence = confidenceOf(gaps),
            intervals = gaps,
        )
    }

    /** HIGH: ≥ 3 intervals with population SD ≤ 3 days. MEDIUM: ≥ 1 interval. LOW: defaults. */
    fun confidenceOf(intervals: List<Int>): CycleConfidence = when {
        intervals.isEmpty() -> CycleConfidence.LOW
        intervals.size >= D.HIGH_CONFIDENCE_MIN_INTERVALS &&
            populationSd(intervals) <= D.HIGH_CONFIDENCE_MAX_SD_DAYS -> CycleConfidence.HIGH
        else -> CycleConfidence.MEDIUM
    }

    /** Population (not sample) standard deviation — the spec's wording. */
    fun populationSd(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    // ---- status -----------------------------------------------------------------------------

    /**
     * Where [day] sits in the cycle, or `null` when nothing is logged yet (or [day] predates the
     * first logged period start).
     */
    fun statusFor(day: Long, entries: List<CycleEntry>): CycleStatus? {
        val anchor = starts(entries).lastOrNull { it <= day } ?: return null
        val averages = averages(entries)
        val cycleLength = averages.cycleLengthDays
        val elapsed = day - anchor
        val cyclesRolled = elapsed / cycleLength
        val cycleStart = anchor + cyclesRolled * cycleLength
        val dayOfCycle = (day - cycleStart + 1).toInt()
        val nextPeriodStart = cycleStart + cycleLength
        val ovulationDay = ovulationDayOf(nextPeriodStart)
        val isLateLuteal = dayOfCycle > cycleLength - D.LATE_LUTEAL_DAYS
        return CycleStatus(
            day = day,
            dayOfCycle = dayOfCycle,
            phase = phaseOf(dayOfCycle, cycleLength, averages.periodLengthDays),
            isLateLuteal = isLateLuteal,
            isPredicted = cyclesRolled > 0,
            cycleLengthDays = cycleLength,
            periodLengthDays = averages.periodLengthDays,
            nextPeriodStart = nextPeriodStart,
            ovulationDay = ovulationDay,
            fertileWindow = fertileWindowOf(ovulationDay),
            confidence = averages.confidence,
        )
    }

    /** [statusFor] over a half-open day range, skipping days that have no cycle yet. */
    fun statusesFor(fromDay: Long, toDayExclusive: Long, entries: List<CycleEntry>): Map<Long, CycleStatus> {
        if (toDayExclusive <= fromDay || entries.isEmpty()) return emptyMap()
        return (fromDay until toDayExclusive).mapNotNull { day ->
            statusFor(day, entries)?.let { day to it }
        }.toMap()
    }

    /** The §P11.1 phase table for a 1-based [dayOfCycle]. */
    fun phaseOf(dayOfCycle: Int, cycleLengthDays: Int, periodLengthDays: Int): CyclePhase {
        val ovulationIndex = cycleLengthDays - D.LUTEAL_PHASE_DAYS
        return when {
            dayOfCycle <= periodLengthDays -> CyclePhase.MENSTRUAL
            dayOfCycle <= ovulationIndex - 2 -> CyclePhase.FOLLICULAR
            dayOfCycle <= ovulationIndex + 1 -> CyclePhase.OVULATION
            else -> CyclePhase.LUTEAL
        }
    }

    /** Ovulation is a fixed 14 days before the next period start. */
    fun ovulationDayOf(nextPeriodStart: Long): Long = nextPeriodStart - D.LUTEAL_PHASE_DAYS

    fun fertileWindowOf(ovulationDay: Long): ClosedRange<Long> =
        (ovulationDay - D.FERTILE_DAYS_BEFORE)..(ovulationDay + D.FERTILE_DAYS_AFTER)

    // ---- forecast ---------------------------------------------------------------------------

    /**
     * The next [cycles] predicted cycles, the first one starting at the next period start on or
     * after [today]. An empty history yields an empty forecast — there is nothing to anchor on.
     */
    fun forecast(
        entries: List<CycleEntry>,
        today: Long,
        cycles: Int = D.FORECAST_CYCLES,
    ): CycleForecast {
        val averages = averages(entries)
        val status = statusFor(today, entries)
        if (status == null || cycles <= 0) {
            return CycleForecast(
                cycles = emptyList(),
                cycleLengthDays = averages.cycleLengthDays,
                periodLengthDays = averages.periodLengthDays,
                confidence = averages.confidence,
            )
        }
        val length = averages.cycleLengthDays
        val predicted = (0 until cycles).map { index ->
            val start = status.nextPeriodStart + index.toLong() * length
            val ovulation = ovulationDayOf(start + length)
            PredictedCycle(
                periodStart = start,
                periodEnd = start + averages.periodLengthDays - 1,
                ovulationDay = ovulation,
                fertileWindow = fertileWindowOf(ovulation),
            )
        }
        return CycleForecast(
            cycles = predicted,
            cycleLengthDays = length,
            periodLengthDays = averages.periodLengthDays,
            confidence = averages.confidence,
        )
    }

    /** Amendment A4: half-up rounding, never `kotlin.math.round`'s half-to-even. */
    private fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()
}

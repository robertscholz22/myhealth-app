package com.myhealth.domain.engine.load

import com.myhealth.domain.engine.load.TrimpDefaults as D
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.util.EngineWarning
import kotlin.math.sqrt

/** One day of the series: the day's summed session TRIMP and how many sessions produced it. */
data class DayLoadInput(val day: Long, val trimp: Double, val sessionCount: Int)

/** ACWR risk zones of PLAN §3.2.3. Derived from `acwr`, not stored in `daily_load`. */
enum class AcwrZone { DETRAINING, OPTIMAL, CAUTION, HIGH_RISK }

/** The flag strings written to `daily_load.flagsCsv` (§2.2.6, §3.2.3). */
object LoadFlags {
    const val RAMP_HIGH: String = "RAMP_HIGH"
    const val HIGH_MONOTONY: String = "HIGH_MONOTONY"
    const val HIGH_STRAIN: String = "HIGH_STRAIN"
    const val NO_REST_DAY_7D: String = "NO_REST_DAY_7D"
    const val INSUFFICIENT_HISTORY: String = "INSUFFICIENT_HISTORY"
}

/** The rolling (secondary) view of §3.2.3: simple 7/28-day means over the same window. */
data class RollingLoad(val atl7: Double, val ctl28: Double, val acwr: Double?)

/**
 * Everything the engine knows about one day. [load] is the row cached in `daily_load`; the rest is
 * the secondary detail the plan asks to expose but the table has no columns for.
 */
data class DayLoadDetail(
    val load: DailyLoad,
    val rolling: RollingLoad,
    val zone: AcwrZone?,
    /** ≥ 21 of the last 28 days carried data (§3.2.3). */
    val reliable: Boolean,
    /** Σ TRIMP over `d-6..d`, the basis of strain and of the ramp flag. */
    val weeklyLoad: Double,
    val warnings: List<EngineWarning>,
)

/**
 * ATL / CTL / ACWR / TSB plus Foster monotony and strain for a contiguous day series
 * (PLAN §3.2.3). Primary metrics are EWMAs (`λ = 2/(N+1)`, seeded at 0 before the series starts);
 * the rolling 7/28-day means are reported alongside as [DayLoadDetail.rolling].
 *
 * `TSB_d = CTL_{d-1} - ATL_{d-1}` — deliberately *yesterday's* values, so today's session does not
 * flatter today's form.
 *
 * Interpretation choices (§3.2.3 does not say):
 * - "days with any data in the last 28" is read as `trimp > 0 || sessionCount > 0`. A recorded rest
 *   day is indistinguishable from a missing day in this input (both are 0/0), and treating an
 *   all-zero window as 28 days of history would report a confident ACWR for someone who has never
 *   trained. Unreliable days keep their ACWR but add [LoadFlags.INSUFFICIENT_HISTORY].
 * - `RAMP_HIGH` and `NO_REST_DAY_7D` only fire once their whole window (14 resp. 7 days) lies
 *   inside the series, so a short prefix cannot fake them.
 * - the ACWR bands are closed at the top: `1.30` is `OPTIMAL` and `1.50` is `CAUTION`.
 * - gaps in the input are filled with zero-load days rather than rejected; days are sorted first.
 */
object LoadSeriesEngine {

    /** The `daily_load` rows for [days] (missing days count as 0 AU). */
    fun compute(days: List<DayLoadInput>, computedAtMillis: Long): List<DailyLoad> =
        computeDetailed(days, computedAtMillis).map { it.load }

    fun computeDetailed(days: List<DayLoadInput>, computedAtMillis: Long): List<DayLoadDetail> {
        val series = normalise(days)
        if (series.isEmpty()) return emptyList()

        val out = ArrayList<DayLoadDetail>(series.size)
        var atlPrev = 0.0
        var ctlPrev = 0.0
        for (i in series.indices) {
            val today = series[i]
            val atl = atlPrev + D.LAMBDA_ATL * (today.trimp - atlPrev)
            val ctl = ctlPrev + D.LAMBDA_CTL * (today.trimp - ctlPrev)
            val acwr = if (ctl > D.MIN_CTL_FOR_ACWR) atl / ctl else null
            val tsb = ctlPrev - atlPrev

            val week = window(series, i, D.ACUTE_WINDOW_DAYS)
            val weeklyLoad = week.sumOf { it.trimp }
            val monotony = monotony(week.map { it.trimp })
            val strain = weeklyLoad * monotony

            val atl7 = weeklyLoad / D.ACUTE_WINDOW_DAYS
            val ctl28 = window(series, i, D.CHRONIC_WINDOW_DAYS).sumOf { it.trimp } /
                D.CHRONIC_WINDOW_DAYS
            val rolling = RollingLoad(
                atl7 = atl7,
                ctl28 = ctl28,
                acwr = if (ctl28 > D.MIN_CTL_FOR_ACWR) atl7 / ctl28 else null,
            )

            val daysWithData = window(series, i, D.CHRONIC_WINDOW_DAYS).count { it.hasData }
            val reliable = daysWithData >= D.MIN_DAYS_WITH_DATA

            val flags = flagsFor(series, i, weeklyLoad, monotony, strain, reliable)
            val warnings = if (reliable) {
                emptyList()
            } else {
                listOf(
                    EngineWarning(
                        EngineWarningCode.INSUFFICIENT_HISTORY,
                        "Only $daysWithData of the last ${D.CHRONIC_WINDOW_DAYS} days carry data " +
                            "(${D.MIN_DAYS_WITH_DATA} needed); ACWR is indicative only.",
                    ),
                )
            }

            out += DayLoadDetail(
                load = DailyLoad(
                    day = today.day,
                    trimp = today.trimp,
                    sessionCount = today.sessionCount,
                    atl = atl,
                    ctl = ctl,
                    acwr = acwr,
                    tsb = tsb,
                    monotony = monotony,
                    strain = strain,
                    recoveryScore = null,
                    recoveryBand = null,
                    recoveryConfidence = 0.0,
                    flags = flags,
                    computedAtMillis = computedAtMillis,
                ),
                rolling = rolling,
                zone = acwr?.let { acwrZone(it) },
                reliable = reliable,
                weeklyLoad = weeklyLoad,
                warnings = warnings,
            )
            atlPrev = atl
            ctlPrev = ctl
        }
        return out
    }

    /** §3.2.3 zone table; bands are closed at the top (`1.30` → OPTIMAL, `1.50` → CAUTION). */
    fun acwrZone(acwr: Double): AcwrZone = when {
        acwr < D.ACWR_DETRAINING_BELOW -> AcwrZone.DETRAINING
        acwr <= D.ACWR_OPTIMAL_MAX -> AcwrZone.OPTIMAL
        acwr <= D.ACWR_CAUTION_MAX -> AcwrZone.CAUTION
        else -> AcwrZone.HIGH_RISK
    }

    /**
     * Foster monotony of a 7-value window: `mean / populationSd`, capped at 3.0; `0.0` for an
     * all-zero week and exactly `3.0` for a perfectly constant non-zero week (sd = 0).
     */
    fun monotony(loads: List<Double>): Double {
        if (loads.isEmpty()) return 0.0
        val n = loads.size
        val mean = loads.sum() / n
        if (mean < D.EPSILON) return 0.0
        val sd = sqrt(loads.sumOf { (it - mean) * (it - mean) } / n)
        if (sd < D.EPSILON) return D.MONOTONY_MAX
        return minOf(mean / sd, D.MONOTONY_MAX)
    }

    /** Population standard deviation (n, not n−1) of a load window — the §3.2.3 definition. */
    fun populationSd(loads: List<Double>): Double {
        if (loads.isEmpty()) return 0.0
        val mean = loads.sum() / loads.size
        return sqrt(loads.sumOf { (it - mean) * (it - mean) } / loads.size)
    }

    private fun flagsFor(
        series: List<Day>,
        i: Int,
        weeklyLoad: Double,
        monotony: Double,
        strain: Double,
        reliable: Boolean,
    ): List<String> {
        val flags = mutableListOf<String>()
        val acute = D.ACUTE_WINDOW_DAYS
        if (i >= 2 * acute - 1) {
            val previousWeek = series.subList(i - 2 * acute + 1, i - acute + 1).sumOf { it.trimp }
            if (previousWeek > D.EPSILON && weeklyLoad / previousWeek > D.RAMP_FLAG_ABOVE) {
                flags += LoadFlags.RAMP_HIGH
            }
        }
        if (monotony > D.MONOTONY_FLAG_ABOVE) flags += LoadFlags.HIGH_MONOTONY
        if (strain > D.STRAIN_FLAG_ABOVE) flags += LoadFlags.HIGH_STRAIN
        if (i >= acute - 1 && series.subList(i - acute + 1, i + 1).none { it.isRestDay }) {
            flags += LoadFlags.NO_REST_DAY_7D
        }
        if (!reliable) flags += LoadFlags.INSUFFICIENT_HISTORY
        return flags
    }

    /** The [size]-day window ending at [i]; days before the series start are simply absent (= 0). */
    private fun window(series: List<Day>, i: Int, size: Int): List<Day> =
        series.subList(maxOf(0, i - size + 1), i + 1)

    private data class Day(val day: Long, val trimp: Double, val sessionCount: Int) {
        val hasData: Boolean get() = trimp > 0.0 || sessionCount > 0
        val isRestDay: Boolean get() = sessionCount == 0 && trimp <= D.EPSILON
    }

    /** Sorts by day, sums duplicates and fills gaps with zero-load days. */
    private fun normalise(days: List<DayLoadInput>): List<Day> {
        if (days.isEmpty()) return emptyList()
        val byDay = mutableMapOf<Long, Day>()
        for (d in days) {
            val existing = byDay[d.day]
            byDay[d.day] = if (existing == null) {
                Day(d.day, maxOf(0.0, d.trimp), maxOf(0, d.sessionCount))
            } else {
                existing.copy(
                    trimp = existing.trimp + maxOf(0.0, d.trimp),
                    sessionCount = existing.sessionCount + maxOf(0, d.sessionCount),
                )
            }
        }
        val first = byDay.keys.min()
        val last = byDay.keys.max()
        return (first..last).map { day -> byDay[day] ?: Day(day, 0.0, 0) }
    }
}

package com.myhealth.domain.engine.load

import com.myhealth.domain.engine.load.TrimpDefaults as D
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.RecoveryComponent
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.util.EngineWarning

/** Component names written into [RecoveryComponent.name] (PLAN §3.3). */
object RecoveryComponents {
    const val SLEEP: String = "SLEEP"
    const val RESTING_HR: String = "RESTING_HR"
    const val LOAD: String = "LOAD"
    const val HRV: String = "HRV"

    const val WEIGHT_SLEEP: Double = 40.0
    const val WEIGHT_RESTING_HR: Double = 25.0
    const val WEIGHT_LOAD: Double = 25.0
    const val WEIGHT_HRV: Double = 10.0
}

/** Recovery-specific flag strings; the load engine's flags are copied through unchanged. */
object RecoveryFlags {
    const val SLEEP_DEBT: String = "SLEEP_DEBT"
}

/**
 * Everything [RecoveryEngine] needs for one day. The caller resolves every window (the engine reads
 * no clock and no zone — bedtimes arrive as minute-of-day so no time zone is needed here).
 *
 * [extraComponents] is the extension point for P9.6: a Body-Battery (or any future) component can be
 * handed in ready-made and it takes part in the same renormalisation as the four built-ins, without
 * touching this engine.
 */
data class RecoveryInput(
    val day: Long,
    /** Last night's sleep, attributed to [day]. Null → the sleep component is dropped. */
    val lastNight: SleepRecord? = null,
    /** Last night's bedtime as minute-of-day (0..1439), for the consistency sub-score. */
    val bedtimeMinuteOfDay: Int? = null,
    /** Bedtime minute-of-day for up to the previous 14 nights (excluding last night). */
    val bedtimeMinutesLast14: List<Int> = emptyList(),
    /** Total sleep minutes per night for the last 7 nights (including last night) — `SLEEP_DEBT`. */
    val sleepMinutesLast7: List<Int> = emptyList(),
    val sleepTargetHours: Double = 8.0,
    /** Today's resting HR, or yesterday's when today has none yet. */
    val restingHrToday: Int? = null,
    /** Resting HR readings of the last 30 days, excluding today (≥ 7 needed). */
    val restingHrLast30: List<Int> = emptyList(),
    /** Today's `daily_load` row; the load component needs a non-null `acwr`. */
    val load: DailyLoad? = null,
    val hrvTodayMs: Double? = null,
    /** HRV readings of the last 7 days, excluding today (≥ 7 needed). */
    val hrvLast7Ms: List<Double> = emptyList(),
    val extraComponents: List<RecoveryComponent> = emptyList(),
)

/**
 * Composite recovery score 0–100 (PLAN §3.3): sleep 40, resting HR 25, load 25, HRV 10. A component
 * whose inputs are missing is dropped and the remaining weights are renormalised, so the score stays
 * on the same 0–100 scale; `confidence` is the share of weight that was actually available.
 *
 * Interpretation choices (§3.3 is silent on both):
 * - a missing **sub**-signal inside the sleep component takes the neutral half of its sub-weight,
 *   exactly as the plan prescribes for absent sleep stages (`qualityPts = 5.0` of 10) — so an absent
 *   bedtime history scores `2.5` of 5 — and adds [EngineWarningCode.LOW_CONFIDENCE]. Dropping the
 *   whole 40-point sleep component over a missing bedtime would be far more distorting.
 * - `confidence = availableWeight / (availableWeight + missingWeight)` rather than
 *   `availableWeight / 100`; identical for the four built-ins (whose weights sum to 100) and still
 *   correct once P9.6 adds a fifth component through [RecoveryInput.extraComponents].
 */
object RecoveryEngine {

    private const val MAX_SCORE = 100.0

    fun compute(input: RecoveryInput): RecoveryState {
        val warnings = mutableListOf<EngineWarning>()
        val components = mutableListOf<RecoveryComponent>()
        var missingWeight = 0.0

        val sleep = sleepComponent(input, warnings)
        if (sleep != null) components += sleep else missingWeight += RecoveryComponents.WEIGHT_SLEEP

        val rhr = restingHrComponent(input)
        if (rhr != null) components += rhr else missingWeight += RecoveryComponents.WEIGHT_RESTING_HR

        val load = loadComponent(input)
        if (load != null) components += load else missingWeight += RecoveryComponents.WEIGHT_LOAD

        val hrv = hrvComponent(input)
        if (hrv != null) components += hrv else missingWeight += RecoveryComponents.WEIGHT_HRV

        components += input.extraComponents

        val availableWeight = components.sumOf { it.maxPoints }
        val flags = buildFlags(input)

        if (availableWeight <= 0.0) {
            warnings += EngineWarning(
                EngineWarningCode.INSUFFICIENT_HISTORY,
                "No sleep, resting-HR, load or HRV data for day ${input.day}.",
            )
            return RecoveryState(
                day = input.day,
                score = null,
                band = null,
                confidence = 0.0,
                components = emptyList(),
                flags = flags,
                warnings = warnings,
            )
        }

        val score = D.roundHalfUp(MAX_SCORE * components.sumOf { it.points } / availableWeight)
            .coerceIn(0, MAX_SCORE.toInt())
        if (missingWeight > 0.0) {
            warnings += EngineWarning(
                EngineWarningCode.LOW_CONFIDENCE,
                "${missingWeight.toInt()} of " +
                    "${(availableWeight + missingWeight).toInt()} recovery weight was unavailable.",
            )
        }
        return RecoveryState(
            day = input.day,
            score = score,
            band = bandFor(score),
            confidence = availableWeight / (availableWeight + missingWeight),
            components = components,
            flags = flags,
            warnings = warnings,
        )
    }

    // ---- components ----------------------------------------------------------------------------

    fun sleepComponent(input: RecoveryInput, warnings: MutableList<EngineWarning>): RecoveryComponent? {
        val night = input.lastNight ?: return null
        if (night.totalSleepMin <= 0) return null
        val total = night.totalSleepMin.toDouble()
        val duration = sleepDurationPoints(total / 60.0)

        val deep = night.deepMin
        val rem = night.remMin
        val quality = if (deep != null && rem != null) {
            5.0 * band(deep / total, 0.13, 0.23) + 5.0 * band(rem / total, 0.20, 0.25)
        } else {
            warnings += EngineWarning(
                EngineWarningCode.LOW_CONFIDENCE,
                "No sleep stages for day ${input.day}; sleep quality scored neutrally.",
            )
            5.0
        }

        val bedtime = input.bedtimeMinuteOfDay
        val medianBedtime = D.median(input.bedtimeMinutesLast14.map { it.toDouble() })
        val consistency = if (bedtime != null && medianBedtime != null) {
            consistencyPoints(circularDeviationMin(bedtime.toDouble(), medianBedtime))
        } else {
            warnings += EngineWarning(
                EngineWarningCode.LOW_CONFIDENCE,
                "No bedtime history for day ${input.day}; sleep consistency scored neutrally.",
            )
            2.5
        }

        return RecoveryComponent(
            name = RecoveryComponents.SLEEP,
            points = (duration + quality + consistency).coerceIn(0.0, RecoveryComponents.WEIGHT_SLEEP),
            maxPoints = RecoveryComponents.WEIGHT_SLEEP,
        )
    }

    fun restingHrComponent(input: RecoveryInput): RecoveryComponent? {
        val today = input.restingHrToday ?: return null
        if (input.restingHrLast30.size < 7) return null
        val base = D.median(input.restingHrLast30.map { it.toDouble() }) ?: return null
        return RecoveryComponent(
            name = RecoveryComponents.RESTING_HR,
            points = restingHrPoints(today - base),
            maxPoints = RecoveryComponents.WEIGHT_RESTING_HR,
        )
    }

    fun loadComponent(input: RecoveryInput): RecoveryComponent? {
        val load = input.load ?: return null
        val acwr = load.acwr ?: return null
        return RecoveryComponent(
            name = RecoveryComponents.LOAD,
            points = loadPoints(acwr = acwr, tsb = load.tsb, monotony = load.monotony),
            maxPoints = RecoveryComponents.WEIGHT_LOAD,
        )
    }

    fun hrvComponent(input: RecoveryInput): RecoveryComponent? {
        val today = input.hrvTodayMs ?: return null
        if (input.hrvLast7Ms.size < 7) return null
        val base = input.hrvLast7Ms.average()
        if (base <= 0.0) return null
        return RecoveryComponent(
            name = RecoveryComponents.HRV,
            points = hrvPoints(today / base),
            maxPoints = RecoveryComponents.WEIGHT_HRV,
        )
    }

    // ---- point formulas (§3.3) -----------------------------------------------------------------

    /** 0–25 points for [hours] of sleep: rises to 25 at 8.5 h, then decays gently, floored at 20. */
    fun sleepDurationPoints(hours: Double): Double = when {
        hours < 4.0 -> 0.0
        hours < 7.0 -> 20.0 * (hours - 4.0) / 3.0
        hours <= 8.5 -> 20.0 + 5.0 * (hours - 7.0) / 1.5
        else -> maxOf(20.0, 25.0 - 2.0 * (hours - 8.5))
    }

    /** `1.0` inside `[lo, hi]`, decaying linearly to 0 outside it. */
    fun band(x: Double, lo: Double, hi: Double): Double = when {
        x in lo..hi -> 1.0
        x < lo -> maxOf(0.0, 1.0 - (lo - x) / lo)
        else -> maxOf(0.0, 1.0 - (x - hi) / hi)
    }

    /** 0–5 points for a bedtime [deviationMin] off the 14-night median. */
    fun consistencyPoints(deviationMin: Double): Double =
        if (deviationMin <= 30.0) 5.0 else maxOf(0.0, 5.0 * (1.0 - (deviationMin - 30.0) / 90.0))

    /** 0–25 points for today's resting HR being [delta] bpm off the 30-day median. */
    fun restingHrPoints(delta: Double): Double = when {
        delta <= -1.0 -> 25.0
        delta >= 7.0 -> 0.0
        else -> 25.0 * (7.0 - delta) / 8.0
    }

    /** 15–25 points for the acute:chronic ratio alone, before the TSB adjustment. */
    fun acwrPoints(acwr: Double): Double = when {
        acwr < 0.50 -> 15.0
        acwr < 0.80 -> 15.0 + 10.0 * (acwr - 0.50) / 0.30
        acwr <= 1.20 -> 25.0
        acwr <= 1.60 -> 25.0 - 20.0 * (acwr - 1.20) / 0.40
        else -> 5.0
    }

    /** 0–25 points: [acwrPoints] nudged by form, then scaled by 0.85 on a monotonous week. */
    fun loadPoints(acwr: Double, tsb: Double, monotony: Double?): Double {
        val tsbAdj = (tsb / 10.0).coerceIn(-5.0, 5.0)
        var points = (acwrPoints(acwr) + tsbAdj).coerceIn(0.0, RecoveryComponents.WEIGHT_LOAD)
        if (monotony != null && monotony > D.MONOTONY_FLAG_ABOVE) points *= 0.85
        return points
    }

    /** 0–10 points for today's HRV as a ratio [r] of the 7-day baseline. */
    fun hrvPoints(r: Double): Double = when {
        r >= 1.0 -> 10.0
        r <= 0.75 -> 0.0
        else -> 10.0 * (r - 0.75) / 0.25
    }

    fun bandFor(score: Int): RecoveryBand = when {
        score >= 80 -> RecoveryBand.FRESH
        score >= 65 -> RecoveryBand.GOOD
        score >= 50 -> RecoveryBand.MODERATE
        score >= 35 -> RecoveryBand.FATIGUED
        else -> RecoveryBand.STRAINED
    }

    /** Circular distance between two minute-of-day values, ≤ 720 min. */
    fun circularDeviationMin(a: Double, b: Double): Double {
        val raw = kotlin.math.abs(a - b) % 1440.0
        return minOf(raw, 1440.0 - raw)
    }

    private fun buildFlags(input: RecoveryInput): List<String> {
        val flags = (input.load?.flags ?: emptyList()).toMutableList()
        if (input.sleepMinutesLast7.isNotEmpty()) {
            val hours = input.sleepMinutesLast7.sum() / 60.0
            if (hours < 7.0 * input.sleepTargetHours - 5.0) flags += RecoveryFlags.SLEEP_DEBT
        }
        return flags
    }
}

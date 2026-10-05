package com.myhealth.domain.engine.load

import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportType
import kotlin.math.floor

/**
 * Every constant, table and shared arithmetic convention of the training-load engines
 * (PLAN §3.2.1–§3.2.3), in one place so the safety bounds can be reviewed on their own.
 *
 * Rounding is **half-up** everywhere (amendment A4) — [roundHalfUp] / [roundTo], never
 * `kotlin.math.round`, which is half-to-even.
 */
object TrimpDefaults {

    // ---- HR bounds (§3.2.1) --------------------------------------------------------------------

    /** Tanaka: `hrMax = 208 - 0.7 * age`. */
    const val TANAKA_INTERCEPT: Double = 208.0
    const val TANAKA_SLOPE: Double = 0.7

    const val HR_MAX_MIN: Int = 150
    const val HR_MAX_MAX: Int = 220
    const val HR_REST_MIN: Int = 30
    const val HR_REST_MAX: Int = 90

    /** Last resort when neither measured nor manual resting HR exists. */
    const val HR_REST_DEFAULT: Int = 60

    /** `hrMax - hrRest` may never be smaller than this; `hrMax` is lifted if it is. */
    const val MIN_HR_RESERVE: Int = 30

    /** Look-back for the resting-HR median and for the observed max HR. */
    const val REST_HR_WINDOW_DAYS: Int = 7
    const val OBSERVED_MAX_HR_WINDOW_DAYS: Int = 365

    // ---- Banister TRIMP (§3.2.2) ---------------------------------------------------------------

    /** Banister's sex-specific exponent `y`. */
    fun banisterY(sex: Sex): Double = when (sex) {
        Sex.FEMALE -> 1.67
        Sex.MALE, Sex.OTHER -> 1.92
    }

    /** The `0.64` of `hrr * 0.64 * exp(y * hrr)` [AU per minute]. */
    const val BANISTER_K: Double = 0.64

    /** A stream gap longer than this cannot inflate the integral; the interval is capped here. */
    const val MAX_SAMPLE_INTERVAL_SEC: Int = 60

    /** `HR_SAMPLES` needs at least this many non-null HR readings. */
    const val MIN_HR_SAMPLES: Int = 10

    /** sRPE → AU factor; calibrated in §3.2.2 (`k ≈ 0.29`, rounded to 0.30). */
    const val RPE_TO_TRIMP: Double = 0.30

    /** The RPE `DURATION_ONLY` assumes when nothing at all is known about the session. */
    const val FALLBACK_RPE: Double = 5.0

    const val TRIMP_MIN: Double = 0.0
    const val TRIMP_MAX: Double = 600.0

    /**
     * TSS → Banister-AU factor for the `POWER_TSS` rung (§3.2.2, P12.2). Calibrated against the
     * HR rungs rather than invented: the Banister-to-TSS ratio of the same session sits at
     * ≈ 1.47–1.58 across IF 0.6–0.85, and 1.5 puts an hour at IF 0.85 (72.25 TSS) at 108.4 AU,
     * which is `load01`'s 108.1 AU for the same hour at 150 bpm. So a threshold hour costs the
     * same whether it is measured by heart rate or by power, and the load series stays comparable
     * across a ride with a strap and a ride with a power meter.
     */
    const val TSS_TO_TRIMP: Double = 1.5

    /**
     * Default session RPE per sport (§3.2.2). Sports **absent** from this table have no default,
     * which is what makes the `DURATION_ONLY` rung of the ladder reachable: the table's "other 5.0"
     * row of the plan is implemented as [FALLBACK_RPE] on that rung, not as a default here (see the
     * ambiguity note on [TrimpCalculator]).
     */
    val DEFAULT_RPE: Map<SportType, Double> = mapOf(
        SportType.SOCCER_MATCH to 8.5,
        SportType.SOCCER_TRAINING to 6.5,
        SportType.HIIT to 8.0,
        SportType.RUN_OUTDOOR to 6.0,
        SportType.RUN_TREADMILL to 6.0,
        SportType.RUN_TRACK to 6.0,
        SportType.RUN_TRAIL to 6.0,
        SportType.STRENGTH to 6.0,
        SportType.CYCLING to 5.0,
        SportType.CYCLING_INDOOR to 5.0,
        SportType.SWIM to 6.0,
        SportType.WALK to 2.0,
        SportType.HIKE to 4.0,
        SportType.MOBILITY to 2.0,
    )

    fun defaultRpeFor(sport: SportType): Double? = DEFAULT_RPE[sport]

    // ---- load series (§3.2.3) ------------------------------------------------------------------

    /** EWMA smoothing factors: `2/(N+1)` for N = 7 (acute) and N = 28 (chronic). */
    const val LAMBDA_ATL: Double = 2.0 / 8.0
    const val LAMBDA_CTL: Double = 2.0 / 29.0

    const val ACUTE_WINDOW_DAYS: Int = 7
    const val CHRONIC_WINDOW_DAYS: Int = 28

    /** ACWR is undefined (null) while the chronic load is still this small. */
    const val MIN_CTL_FOR_ACWR: Double = 1.0

    /** `reliable` needs this many of the last [CHRONIC_WINDOW_DAYS] days to carry data. */
    const val MIN_DAYS_WITH_DATA: Int = 21

    const val ACWR_DETRAINING_BELOW: Double = 0.80
    const val ACWR_OPTIMAL_MAX: Double = 1.30
    const val ACWR_CAUTION_MAX: Double = 1.50

    const val MONOTONY_MAX: Double = 3.0
    const val MONOTONY_FLAG_ABOVE: Double = 2.0
    const val STRAIN_FLAG_ABOVE: Double = 6000.0
    const val RAMP_FLAG_ABOVE: Double = 1.15

    /** Values below this count as zero in the monotony edge cases (mean / sd). */
    const val EPSILON: Double = 1e-6

    // ---- shared arithmetic ---------------------------------------------------------------------

    /** Half-up rounding to a multiple of [step] (amendment A4). */
    fun roundTo(value: Double, step: Double): Double = floor(value / step + 0.5) * step

    /** Half-up rounding to the nearest integer (amendment A4). */
    fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()

    /** Median of [values], or null when empty. Even counts average the two middle values. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}

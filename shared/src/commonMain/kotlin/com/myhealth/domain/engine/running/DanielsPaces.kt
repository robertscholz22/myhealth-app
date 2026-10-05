package com.myhealth.domain.engine.running

import com.myhealth.domain.engine.load.TrimpDefaults

/**
 * The five Daniels training paces, as fractions of VDOT (PLAN §3.10.1).
 *
 * The fraction is the share of VO₂max the pace is run at: easy running sits at 63 % of the
 * athlete's VDOT, a marathon at 83 %, threshold ("comfortably hard") at 88 %, VO₂max intervals at
 * 98 % and short repetitions above it, at 106 %.
 */
enum class DanielsPace(val vdotFraction: Double) {
    EASY(0.63),
    MARATHON(0.83),
    THRESHOLD(0.88),
    INTERVAL(0.98),
    REPETITION(1.06),
}

/**
 * Daniels' training paces derived from the §3.4 VDOT (PLAN §3.10.1).
 *
 * §3.4 gives the oxygen cost of a velocity, `vo2(v) = -4.60 + 0.182258 v + 0.000104 v²`. A training
 * pace is the inverse question — *which velocity costs a given fraction of my VDOT?* — so the
 * quadratic is solved for `v` ([VdotCalculator.velocityFor]) and turned into seconds per kilometre:
 *
 * ```
 * v(pct)       = (-0.182258 + sqrt(0.182258² + 4·0.000104·(4.60 + vdot·pct))) / (2·0.000104)   // m/min
 * paceSecPerKm = 60000 / v
 * ```
 *
 * At VDOT 50 the table is E 334 · M 268 · T 255 · I 234 · R **219** s/km (the plan's `vd05`
 * quotes 220; the exact value is 219.47, inside the test's ±1 tolerance — see PLAN §3.10).
 *
 * Pure arithmetic, no clamping: the caller decides what a pace for an implausible VDOT means.
 */
object DanielsPaces {

    /** Seconds per kilometre at [fraction] of [vdot]; `null` for a non-positive input. */
    fun secPerKmExact(vdot: Double, fraction: Double): Double? {
        val velocity = VdotCalculator.velocityFor(vdot, fraction) ?: return null
        return SEC_PER_MIN_TIMES_METERS_PER_KM / velocity
    }

    /** Seconds per kilometre for one named [pace]; `null` for a non-positive [vdot]. */
    fun secPerKmExact(vdot: Double, pace: DanielsPace): Double? =
        secPerKmExact(vdot, pace.vdotFraction)

    /** [secPerKmExact] rounded half-up to whole seconds (amendment A4). */
    fun secPerKm(vdot: Double, pace: DanielsPace): Int? =
        secPerKmExact(vdot, pace)?.let { TrimpDefaults.roundHalfUp(it) }

    /** The whole table in E → R order; empty for a non-positive [vdot]. */
    fun table(vdot: Double): Map<DanielsPace, Int> =
        DanielsPace.entries.mapNotNull { pace -> secPerKm(vdot, pace)?.let { pace to it } }.toMap()

    /** `60 s/min × 1000 m/km`: `m/min` → `s/km` is a division into this constant. */
    private const val SEC_PER_MIN_TIMES_METERS_PER_KM: Double = 60_000.0
}

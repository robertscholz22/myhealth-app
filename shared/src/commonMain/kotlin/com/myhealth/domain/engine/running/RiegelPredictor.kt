package com.myhealth.domain.engine.running

import com.myhealth.domain.model.RunningBest
import kotlin.math.pow

/** One Riegel-extrapolated time, with the effort it was extrapolated from. */
data class RacePrediction(
    val distanceMeters: Double,
    val timeSec: Double,
    val source: RunningBest,
) {
    val paceSecPerKm: Double get() = timeSec / (distanceMeters / 1000.0)
}

/**
 * Riegel's endurance model (PLAN §3.4): `T2 = T1 * (D2 / D1) ^ 1.06`.
 *
 * The source effort is the effort of the last 180 days over at least 3 km with the **highest
 * VDOT** (POLISH-4). The plan's original "largest distance wins" rule let a jogged 10 km
 * (56:30, VDOT 34.7) outrank a raced 5 km (20:29, VDOT 48.2) and predict times slower than the
 * athlete's own PR; ranking by VDOT picks the effort that actually represents current fitness.
 * Ties (identical VDOT) go to the longer distance, which extrapolates with less error.
 */
object RiegelPredictor {

    const val EXPONENT: Double = 1.06
    const val MIN_SOURCE_DISTANCE_M: Double = 3000.0
    const val MAX_SOURCE_AGE_DAYS: Long = 180L

    /** `T2 = T1 * (D2/D1)^1.06`; `null` when the source is degenerate. */
    fun predictSec(sourceDistanceMeters: Double, sourceTimeSec: Double, targetDistanceMeters: Double): Double? {
        if (sourceDistanceMeters <= 0.0 || sourceTimeSec <= 0.0 || targetDistanceMeters <= 0.0) return null
        return sourceTimeSec * (targetDistanceMeters / sourceDistanceMeters).pow(EXPONENT)
    }

    /**
     * The effort to extrapolate from: `day >= today - 180`, `distance >= 3000`, highest VDOT,
     * longer distance on a tie. `null` when the athlete has no qualifying effort.
     */
    fun pickSource(bests: List<RunningBest>, today: Long): RunningBest? = bests
        .filter {
            it.distanceMeters >= MIN_SOURCE_DISTANCE_M &&
                it.day >= today - MAX_SOURCE_AGE_DAYS &&
                it.timeSec > 0
        }
        .maxWithOrNull(
            compareBy<RunningBest> { vdotOf(it) }.thenBy { it.distanceMeters },
        )

    /** The VDOT a stored effort corresponds to; `0.0` for a degenerate row (never the maximum). */
    fun vdotOf(best: RunningBest): Double =
        VdotCalculator.vdot(best.distanceMeters, best.timeSec.toDouble()) ?: 0.0

    /** Predictions for every canonical distance (or [targets]) from the picked source effort. */
    fun predictAll(
        bests: List<RunningBest>,
        today: Long,
        targets: List<Double> = CanonicalDistances.ALL,
    ): List<RacePrediction> {
        val source = pickSource(bests, today) ?: return emptyList()
        return targets.mapNotNull { target ->
            predictSec(source.distanceMeters, source.timeSec.toDouble(), target)
                ?.let { RacePrediction(distanceMeters = target, timeSec = it, source = source) }
        }
    }
}

package com.myhealth.domain.engine.running

/**
 * The eight distances a `running_best` row may be stored for (PLAN §2.2.6 / §3.4). Anything else —
 * a 4 × 400 m session, a 7 km tempo — is not a PR distance and is never persisted.
 */
object CanonicalDistances {

    const val ONE_KM: Double = 1000.0
    const val MILE: Double = 1609.34
    const val THREE_KM: Double = 3000.0
    const val FIVE_KM: Double = 5000.0
    const val TEN_KM: Double = 10000.0
    const val FIFTEEN_KM: Double = 15000.0
    const val HALF_MARATHON: Double = 21097.5
    const val MARATHON: Double = 42195.0

    /** Ascending — callers rely on the order when rendering the PR table. */
    val ALL: List<Double> = listOf(
        ONE_KM, MILE, THREE_KM, FIVE_KM, TEN_KM, FIFTEEN_KM, HALF_MARATHON, MARATHON,
    )

    /** The canonical distances an activity of [totalDistanceMeters] can possibly contain. */
    fun upTo(totalDistanceMeters: Double): List<Double> = ALL.filter { it <= totalDistanceMeters }

    /** True for a distance that is (within a metre) one of [ALL]. */
    fun isCanonical(distanceMeters: Double): Boolean =
        ALL.any { kotlin.math.abs(it - distanceMeters) < 1.0 }
}

package com.myhealth.domain.engine.activity

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.SportGroup
import kotlin.math.abs
import kotlin.math.max

/**
 * The four values the §2.4 predicate needs. A tiny value type rather than the full
 * [ActivitySession] so callers can match a database row, a decoded import row or a domain object
 * without building one of the other.
 */
data class MatchKey(
    val sportGroup: SportGroup,
    val startAtMillis: Long,
    val durationSec: Int,
    val distanceMeters: Double?,
)

fun ActivitySession.toMatchKey(): MatchKey =
    MatchKey(sportGroup, startAtMillis, durationSec, distanceMeters)

fun ActivitySummary.toMatchKey(): MatchKey =
    MatchKey(sportGroup, startAtMillis, durationSec, distanceMeters)

/**
 * De-duplication predicate (PLAN §2.4, normative). Two source records describe the same
 * real-world activity iff **all** of:
 *
 * 1. same `sportGroup`, and
 * 2. starts within 3 minutes of each other, and
 * 3. durations within `max(60 s, 5 %)` of each other, and
 * 4. distances agree: both null, one null, or within `max(100 m, 2 %)`.
 *
 * Pure and symmetric: `matches(a, b) == matches(b, a)` — every tolerance is computed from the
 * larger of the two values, never from "the first one".
 */
object ActivityMatcher {

    const val START_TOLERANCE_MILLIS: Long = 180_000L
    const val DURATION_TOLERANCE_SEC: Double = 60.0
    const val DURATION_TOLERANCE_FRACTION: Double = 0.05
    const val DISTANCE_TOLERANCE_METERS: Double = 100.0
    const val DISTANCE_TOLERANCE_FRACTION: Double = 0.02

    fun matches(a: MatchKey, b: MatchKey): Boolean =
        a.sportGroup == b.sportGroup &&
            startsAgree(a.startAtMillis, b.startAtMillis) &&
            durationsAgree(a.durationSec, b.durationSec) &&
            distancesAgree(a.distanceMeters, b.distanceMeters)

    fun matches(a: ActivitySession, b: ActivitySession): Boolean =
        matches(a.toMatchKey(), b.toMatchKey())

    /** The first candidate in [candidates] that describes the same activity as [target]. */
    fun firstMatch(candidates: List<MatchKey>, target: MatchKey): MatchKey? =
        candidates.firstOrNull { matches(it, target) }

    fun startsAgree(aMillis: Long, bMillis: Long): Boolean =
        abs(aMillis - bMillis) <= START_TOLERANCE_MILLIS

    fun durationsAgree(aSec: Int, bSec: Int): Boolean {
        val tolerance = max(DURATION_TOLERANCE_SEC, DURATION_TOLERANCE_FRACTION * max(aSec, bSec))
        return abs(aSec - bSec) <= tolerance
    }

    /** Missing distance is not disagreement: only two present-and-far-apart values reject. */
    fun distancesAgree(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return true
        val tolerance = max(DISTANCE_TOLERANCE_METERS, DISTANCE_TOLERANCE_FRACTION * max(a, b))
        return abs(a - b) <= tolerance
    }
}

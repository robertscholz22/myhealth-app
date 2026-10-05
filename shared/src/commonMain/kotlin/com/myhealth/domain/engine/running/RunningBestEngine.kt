package com.myhealth.domain.engine.running

import com.myhealth.domain.engine.common.SplitFinder
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.SportType
import kotlin.math.abs
import kotlin.math.floor

/** How a best effort was obtained (`running_best.method`, PLAN §2.2.6). */
enum class PrMethod { FULL_ACTIVITY, BEST_SPLIT, MANUAL }

/** One canonical-distance effort found inside one activity (PLAN §3.4). */
data class BestEffort(
    val distanceMeters: Double,
    val timeSec: Double,
    val method: PrMethod,
    /** The time is scaled or the samples are too sparse to trust the split to the second. */
    val isEstimated: Boolean,
) {
    val paceSecPerKm: Double get() = timeSec / (distanceMeters / 1000.0)
}

/** One activity's inputs for [RunningBestEngine]; the caller supplies the treadmill setting. */
data class RunEffortInput(
    val sportType: SportType,
    val durationSec: Int,
    val totalDistanceMeters: Double? = null,
    val streams: ActivityStreams? = null,
    /** `settings.includeTreadmillInPrs` (default false, PLAN §3.4). */
    val includeTreadmillInPrs: Boolean = false,
)

/**
 * Best efforts per canonical distance for one run (PLAN §3.4).
 *
 * Two methods: **A**, the whole activity scaled onto a canonical distance when it is within
 * `max(1 %, 50 m)` of it; **B**, the fastest split of that distance inside the distance/time stream,
 * found with a two-pointer scan that interpolates *both* edges (once with the right edge anchored on
 * a sample and the left interpolated, once the other way round) so a 5 s sampling interval does not
 * quantise the result.
 *
 * Interpretation choices (§3.4 leaves both open):
 * - when both methods produce a candidate for the same distance the non-estimated one wins, and
 *   between two equally-trustworthy candidates the faster one does (a best effort is a minimum).
 * - the quality gate `medianIntervalSec <= 10` only marks a split estimated; a stream that does not
 *   reach the distance at all yields no split candidate rather than an extrapolated one.
 *
 * Every candidate must pass the pace sanity window `140..900 s/km`, so a GPS blow-up or a
 * mis-scaled treadmill distance cannot become a PR.
 */
object RunningBestEngine {

    /** Runs that count as PR-eligible; the treadmill joins only behind the setting (§3.4). */
    val ELIGIBLE_SPORTS: Set<SportType> =
        setOf(SportType.RUN_OUTDOOR, SportType.RUN_TRACK, SportType.RUN_TRAIL)

    const val MIN_PACE_SEC_PER_KM: Double = 140.0
    const val MAX_PACE_SEC_PER_KM: Double = 900.0

    /** `|total - D| <= max(1 % of D, 50 m)` for method A. */
    const val FULL_ACTIVITY_TOLERANCE_SHARE: Double = 0.01
    const val FULL_ACTIVITY_TOLERANCE_M: Double = 50.0

    /** Beyond this much scaling the method-A time is flagged estimated. */
    const val FULL_ACTIVITY_EXACT_M: Double = 5.0

    /** A split from samples further apart than this is estimated (§3.4 quality gate). */
    const val MAX_TRUSTED_INTERVAL_SEC: Double = 10.0

    fun isEligible(sportType: SportType, includeTreadmillInPrs: Boolean): Boolean =
        sportType in ELIGIBLE_SPORTS ||
            (sportType == SportType.RUN_TREADMILL && includeTreadmillInPrs)

    /** One [BestEffort] per canonical distance the activity actually contains. */
    fun compute(input: RunEffortInput): List<BestEffort> {
        if (!isEligible(input.sportType, input.includeTreadmillInPrs)) return emptyList()
        val samples = cleanSamples(input.streams)
        val streamDistance = samples?.let { it.distance.last() - it.distance.first() } ?: 0.0
        val total = maxOf(input.totalDistanceMeters ?: 0.0, streamDistance)
        if (total <= 0.0) return emptyList()

        return CanonicalDistances.upTo(total).mapNotNull { target ->
            val candidates = listOfNotNull(
                splitCandidate(samples, input.streams, target),
                fullActivityCandidate(input, target),
            ).filter { plausible(it) }
            candidates.minWithOrNull(
                compareBy<BestEffort> { it.isEstimated }.thenBy { it.timeSec },
            )
        }
    }

    /** The same computation off a loaded session, mapped to storable rows (P5.5). */
    fun computeRows(
        session: ActivitySession,
        includeTreadmillInPrs: Boolean = false,
        createdAtMillis: Long = session.updatedAtMillis,
    ): List<RunningBest> = toRows(
        efforts = compute(
            RunEffortInput(
                sportType = session.sportType,
                durationSec = session.durationSec,
                totalDistanceMeters = session.distanceMeters,
                streams = session.streams,
                includeTreadmillInPrs = includeTreadmillInPrs,
            ),
        ),
        activityId = session.id,
        day = session.day,
        createdAtMillis = createdAtMillis,
    )

    /** Maps engine output onto `running_best` rows (`id = 0` → the DAO assigns it). */
    fun toRows(
        efforts: List<BestEffort>,
        activityId: Long?,
        day: Long,
        createdAtMillis: Long,
    ): List<RunningBest> = efforts.map { effort ->
        RunningBest(
            id = 0L,
            distanceMeters = effort.distanceMeters,
            timeSec = roundHalfUp(effort.timeSec),
            activityId = activityId,
            day = day,
            method = effort.method.name,
            isEstimated = effort.isEstimated,
            paceSecPerKm = roundHalfUp(effort.paceSecPerKm),
            createdAtMillis = createdAtMillis,
        )
    }

    /** The PR table: the fastest row per distance, ascending by distance (mirrors the DAO query). */
    fun bestPerDistance(rows: List<RunningBest>): List<RunningBest> = rows
        .groupBy { it.distanceMeters }
        .mapNotNull { (_, forDistance) -> forDistance.minByOrNull { it.timeSec } }
        .sortedBy { it.distanceMeters }

    // ---- method A ------------------------------------------------------------------------------

    private fun fullActivityCandidate(input: RunEffortInput, target: Double): BestEffort? {
        val total = input.totalDistanceMeters ?: return null
        if (total <= 0.0 || input.durationSec <= 0) return null
        val delta = abs(total - target)
        val tolerance = maxOf(FULL_ACTIVITY_TOLERANCE_SHARE * target, FULL_ACTIVITY_TOLERANCE_M)
        if (delta > tolerance) return null
        return BestEffort(
            distanceMeters = target,
            timeSec = input.durationSec * target / total,
            method = PrMethod.FULL_ACTIVITY,
            isEstimated = delta > FULL_ACTIVITY_EXACT_M,
        )
    }

    // ---- method B ------------------------------------------------------------------------------

    private fun splitCandidate(
        samples: Samples?,
        streams: ActivityStreams?,
        target: Double,
    ): BestEffort? {
        if (samples == null) return null
        val timeSec = bestSplitSec(samples.offsets, samples.distance, target) ?: return null
        val interval = medianIntervalSec(samples, streams)
        return BestEffort(
            distanceMeters = target,
            timeSec = timeSec,
            method = PrMethod.BEST_SPLIT,
            isEstimated = interval > MAX_TRUSTED_INTERVAL_SEC,
        )
    }

    /**
     * Fastest time to cover [target] metres inside the stream, or `null` when the stream never
     * covers it. Delegates to [SplitFinder] — the scan moved there in P12.2 so the cycling
     * best-effort engine can reuse it; this entry point stays for the `pr` tests and callers.
     */
    fun bestSplitSec(offsetsSec: DoubleArray, cumulativeMeters: DoubleArray, target: Double): Double? =
        SplitFinder.bestSplitSec(offsetsSec, cumulativeMeters, target)

    // ---- sample hygiene ------------------------------------------------------------------------

    private class Samples(val offsets: DoubleArray, val distance: DoubleArray)

    /**
     * Drops samples with a non-increasing offset, a non-finite value or a decreasing cumulative
     * distance (GPS jitter), so the two-pointer scan can assume both axes are monotone.
     */
    private fun cleanSamples(streams: ActivityStreams?): Samples? {
        val distances = streams?.distanceMeters ?: return null
        val offsets = streams.sampleOffsetsSec
        val n = minOf(offsets.size, distances.size)
        if (n < 2) return null
        val outOffsets = ArrayList<Double>(n)
        val outDistance = ArrayList<Double>(n)
        for (index in 0 until n) {
            val t = offsets[index].toDouble()
            val d = distances[index]
            if (!t.isFinite() || !d.isFinite()) continue
            if (outOffsets.isNotEmpty() && t <= outOffsets.last()) continue
            val monotoneDistance = if (outDistance.isEmpty()) d else maxOf(d, outDistance.last())
            outOffsets += t
            outDistance += monotoneDistance
        }
        if (outOffsets.size < 2) return null
        return Samples(outOffsets.toDoubleArray(), outDistance.toDoubleArray())
    }

    /** `activity_stream.medianIntervalSec` when it is populated, else measured from the offsets. */
    private fun medianIntervalSec(samples: Samples, streams: ActivityStreams?): Double {
        val stored = streams?.medianIntervalSec ?: 0.0
        if (stored > 0.0) return stored
        val diffs = (1 until samples.offsets.size)
            .map { samples.offsets[it] - samples.offsets[it - 1] }
            .sorted()
        if (diffs.isEmpty()) return Double.MAX_VALUE
        val mid = diffs.size / 2
        return if (diffs.size % 2 == 1) diffs[mid] else (diffs[mid - 1] + diffs[mid]) / 2.0
    }

    private fun plausible(effort: BestEffort): Boolean =
        effort.timeSec > 0.0 &&
            effort.paceSecPerKm >= MIN_PACE_SEC_PER_KM &&
            effort.paceSecPerKm <= MAX_PACE_SEC_PER_KM

    /** Half-up rounding (amendment A4) — `running_best` stores whole seconds. */
    private fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()
}

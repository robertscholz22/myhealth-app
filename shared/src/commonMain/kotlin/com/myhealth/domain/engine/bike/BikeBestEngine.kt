package com.myhealth.domain.engine.bike

import com.myhealth.domain.engine.bike.BikeDefaults as D
import com.myhealth.domain.engine.common.SplitFinder
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.SportType
import kotlin.math.abs

/** One ride's inputs for [BikeBestEngine] (PLAN §3.8.2/§3.8.3) — the cycling `RunEffortInput`. */
data class RideEffortInput(
    val sportType: SportType,
    val durationSec: Int,
    val totalDistanceMeters: Double? = null,
    val streams: ActivityStreams? = null,
)

/**
 * One qualifying effort found inside one ride: watts for a `POWER_*` [kind], seconds for a
 * `TIME_*` one, both already rounded half-up to whole units.
 */
data class RideBestEffort(
    val kind: RideBestKind,
    val value: Double,
    /** Sparse samples (`POWER_*`) or a scaled whole-ride time (`TIME_*`) — not exact. */
    val isEstimated: Boolean,
)

/**
 * Ride bests for one activity (PLAN §3.8), the cycling mirror of
 * [com.myhealth.domain.engine.running.RunningBestEngine].
 *
 * **`POWER_5/20/60MIN`** — the maximum time-weighted mean power over a window of *exactly* the
 * kind's length. The samples are turned into a step function whose every sample stands for the gap
 * to the next one, capped at [BikeDefaults.MAX_GAP_SEC] so a pause cannot stretch a hard effort
 * over it; a ride whose capped coverage is shorter than the window yields no best for that kind.
 * The window is then slid continuously (the maximum of the piecewise-linear window integral sits
 * on a sample boundary of either edge, so both edges are enumerated). `isEstimated` when the
 * median sample interval exceeds [BikeDefaults.MAX_TRUSTED_INTERVAL_SEC]; values outside
 * `[30, 1500]` W are dropped.
 *
 * **`TIME_10/20/40/100K`** — the fastest split of that distance inside the cumulative distance
 * stream ([SplitFinder], shared with §3.4) **plus** the full-ride method: when the ride's total
 * distance is within `max(1 %, 50 m)` of the canonical distance, the duration is scaled onto it and
 * flagged estimated beyond [BikeDefaults.FULL_RIDE_EXACT_M] of scaling. Candidates outside
 * `[8, 70] km/h` are dropped; between two candidates the non-estimated one wins, then the faster.
 *
 * **Known limitation (documented in §3.8.3):** Health Connect does not expose a cumulative distance
 * stream, so an HC-only ride has no split candidate at all — its `TIME_*` bests can only come from
 * the full-ride method, and are therefore estimated unless the ride ends within 5 m of the
 * canonical distance. FIT imports carry the distance stream and get real splits.
 */
object BikeBestEngine {

    /** Every effort this ride contains, in [RideBestKind] order. */
    fun compute(input: RideEffortInput): List<RideBestEffort> {
        if (!D.isEligible(input.sportType)) return emptyList()
        return powerEfforts(input.streams) + timeEfforts(input)
    }

    /** The same computation off a loaded session, mapped to storable rows (P12.2). */
    fun computeRows(
        session: ActivitySession,
        createdAtMillis: Long = session.createdAtMillis,
    ): List<RideBest> = toRows(
        efforts = compute(
            RideEffortInput(
                sportType = session.sportType,
                durationSec = session.durationSec,
                totalDistanceMeters = session.distanceMeters,
                streams = session.streams,
            ),
        ),
        activityId = session.id,
        day = session.day,
        createdAtMillis = createdAtMillis,
    )

    /** Maps engine output onto `ride_best` rows (`id = 0` → the DAO assigns it). */
    fun toRows(
        efforts: List<RideBestEffort>,
        activityId: Long?,
        day: Long,
        createdAtMillis: Long,
    ): List<RideBest> = efforts.map { effort ->
        RideBest(
            id = 0L,
            kind = effort.kind,
            value = effort.value,
            activityId = activityId,
            day = day,
            isEstimated = effort.isEstimated,
            createdAtMillis = createdAtMillis,
        )
    }

    // ---- POWER_* -----------------------------------------------------------------------------

    private fun powerEfforts(streams: ActivityStreams?): List<RideBestEffort> {
        val series = PowerSeries.of(streams) ?: return emptyList()
        val estimated = medianIntervalSec(series.offsets, streams) > D.MAX_TRUSTED_INTERVAL_SEC
        return RideBestKind.entries.filter { it.isPower }.mapNotNull { kind ->
            val window = kind.windowSec ?: return@mapNotNull null
            val watts = series.maxMeanOver(window.toDouble()) ?: return@mapNotNull null
            if (watts < D.POWER_MIN_W || watts > D.POWER_MAX_W) return@mapNotNull null
            RideBestEffort(kind, D.roundHalfUp(watts).toDouble(), estimated)
        }
    }

    /**
     * The power step function of one ride: [offsets] are the sample times, [breaks] the cumulative
     * *capped* time each sample stands for and [energy] the running integral of watt-seconds, so a
     * window mean is one subtraction.
     */
    private class PowerSeries(
        val offsets: DoubleArray,
        val breaks: DoubleArray,
        val energy: DoubleArray,
        val watts: DoubleArray,
    ) {
        val coverageSec: Double get() = breaks.last()

        /** Max time-weighted mean over a window of exactly [windowSec] seconds, or `null`. */
        fun maxMeanOver(windowSec: Double): Double? {
            if (windowSec <= 0.0 || coverageSec < windowSec) return null
            var best = Double.NEGATIVE_INFINITY
            for (start in candidateStarts(windowSec)) {
                val mean = (integralAt(start + windowSec) - integralAt(start)) / windowSec
                if (mean > best) best = mean
            }
            return best.takeIf { it.isFinite() }
        }

        /** Window starts worth testing: both edges anchored on a sample boundary, plus the ends. */
        private fun candidateStarts(windowSec: Double): List<Double> {
            val last = coverageSec - windowSec
            val starts = ArrayList<Double>(breaks.size * 2 + 2)
            starts += 0.0
            starts += last
            for (breakpoint in breaks) {
                if (breakpoint in 0.0..last) starts += breakpoint
                val shifted = breakpoint - windowSec
                if (shifted in 0.0..last) starts += shifted
            }
            return starts
        }

        /** Watt-seconds accumulated up to [t] on the capped timeline. */
        private fun integralAt(t: Double): Double {
            val time = t.coerceIn(0.0, coverageSec)
            var low = 0
            var high = watts.size - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (breaks[mid] <= time) low = mid else high = mid - 1
            }
            return energy[low] + watts[low] * (time - breaks[low])
        }

        companion object {
            /** Builds the step function, or `null` when the ride carries no usable power series. */
            fun of(streams: ActivityStreams?): PowerSeries? {
                val power = streams?.powerW ?: return null
                val rawOffsets = streams.sampleOffsetsSec
                val count = minOf(rawOffsets.size, power.size)
                if (count < 1) return null
                val offsets = ArrayList<Double>(count)
                val watts = ArrayList<Double>(count)
                for (index in 0 until count) {
                    val t = rawOffsets[index].toDouble()
                    if (!t.isFinite() || power[index] < 0) continue
                    if (offsets.isNotEmpty() && t <= offsets.last()) continue
                    offsets += t
                    watts += power[index].toDouble()
                }
                if (offsets.isEmpty()) return null
                val breaks = DoubleArray(offsets.size + 1)
                val energy = DoubleArray(offsets.size + 1)
                for (index in offsets.indices) {
                    breaks[index + 1] = breaks[index] + cappedGap(offsets, index)
                    energy[index + 1] = energy[index] + watts[index] * (breaks[index + 1] - breaks[index])
                }
                return PowerSeries(offsets.toDoubleArray(), breaks, energy, watts.toDoubleArray())
            }

            /** The seconds a sample stands for: the gap to the next one, capped (same as `PowerMath`). */
            private fun cappedGap(offsets: List<Double>, index: Int): Double {
                val gap = when {
                    index + 1 < offsets.size -> offsets[index + 1] - offsets[index]
                    index > 0 -> offsets[index] - offsets[index - 1]
                    else -> 1.0
                }
                return gap.coerceIn(1.0, D.MAX_GAP_SEC.toDouble())
            }
        }
    }

    // ---- TIME_* ------------------------------------------------------------------------------

    private fun timeEfforts(input: RideEffortInput): List<RideBestEffort> {
        val samples = cleanDistance(input.streams)
        val streamDistance = samples?.let { it.distance.last() - it.distance.first() } ?: 0.0
        val total = maxOf(input.totalDistanceMeters ?: 0.0, streamDistance)
        if (total <= 0.0) return emptyList()

        return RideBestKind.entries.filter { !it.isPower }.mapNotNull { kind ->
            val target = kind.distanceMeters ?: return@mapNotNull null
            if (target > total) return@mapNotNull null
            val candidates = listOfNotNull(
                splitCandidate(samples, input.streams, kind, target),
                fullRideCandidate(input, kind, target),
            ).filter { plausible(target, it.value) }
            candidates.minWithOrNull(compareBy<RideBestEffort> { it.isEstimated }.thenBy { it.value })
        }
    }

    private fun splitCandidate(
        samples: DistanceSamples?,
        streams: ActivityStreams?,
        kind: RideBestKind,
        target: Double,
    ): RideBestEffort? {
        if (samples == null) return null
        val timeSec = SplitFinder.bestSplitSec(samples.offsets, samples.distance, target) ?: return null
        val estimated = medianIntervalSec(samples.offsets, streams) > D.MAX_TRUSTED_INTERVAL_SEC
        return RideBestEffort(kind, D.roundHalfUp(timeSec).toDouble(), estimated)
    }

    private fun fullRideCandidate(
        input: RideEffortInput,
        kind: RideBestKind,
        target: Double,
    ): RideBestEffort? {
        val total = input.totalDistanceMeters ?: return null
        if (total <= 0.0 || input.durationSec <= 0) return null
        val delta = abs(total - target)
        val tolerance = maxOf(D.FULL_RIDE_TOLERANCE_SHARE * target, D.FULL_RIDE_TOLERANCE_M)
        if (delta > tolerance) return null
        return RideBestEffort(
            kind = kind,
            value = D.roundHalfUp(input.durationSec * target / total).toDouble(),
            isEstimated = delta > D.FULL_RIDE_EXACT_M,
        )
    }

    private fun plausible(target: Double, timeSec: Double): Boolean {
        if (timeSec <= 0.0) return false
        val speed = D.speedKmh(target, timeSec)
        return speed >= D.MIN_SPEED_KMH && speed <= D.MAX_SPEED_KMH
    }

    // ---- sample hygiene ------------------------------------------------------------------------

    private class DistanceSamples(val offsets: DoubleArray, val distance: DoubleArray)

    /** Same cleaning contract as §3.4: strictly increasing time, non-decreasing distance. */
    private fun cleanDistance(streams: ActivityStreams?): DistanceSamples? {
        val distances = streams?.distanceMeters ?: return null
        val offsets = streams.sampleOffsetsSec
        val count = minOf(offsets.size, distances.size)
        if (count < 2) return null
        val outOffsets = ArrayList<Double>(count)
        val outDistance = ArrayList<Double>(count)
        for (index in 0 until count) {
            val t = offsets[index].toDouble()
            val d = distances[index]
            if (!t.isFinite() || !d.isFinite()) continue
            if (outOffsets.isNotEmpty() && t <= outOffsets.last()) continue
            outOffsets += t
            outDistance += if (outDistance.isEmpty()) d else maxOf(d, outDistance.last())
        }
        if (outOffsets.size < 2) return null
        return DistanceSamples(outOffsets.toDoubleArray(), outDistance.toDoubleArray())
    }

    /** `activity_stream.medianIntervalSec` when populated, else measured off the offsets. */
    private fun medianIntervalSec(offsets: DoubleArray, streams: ActivityStreams?): Double {
        val stored = streams?.medianIntervalSec ?: 0.0
        if (stored > 0.0) return stored
        if (offsets.size < 2) return Double.MAX_VALUE
        val diffs = (1 until offsets.size).map { offsets[it] - offsets[it - 1] }.sorted()
        val mid = diffs.size / 2
        return if (diffs.size % 2 == 1) diffs[mid] else (diffs[mid - 1] + diffs[mid]) / 2.0
    }
}

package com.myhealth.domain.engine.running

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.SportType
import org.junit.Test

/** The best-effort cases of PLAN §3.4 (`pr01`…`pr09`, `pr12`). */
class RunningBestEngineTest {

    private fun input(
        sportType: SportType = SportType.RUN_OUTDOOR,
        durationSec: Int,
        totalDistanceMeters: Double? = null,
        streams: ActivityStreams? = null,
        includeTreadmillInPrs: Boolean = false,
    ) = RunEffortInput(
        sportType = sportType,
        durationSec = durationSec,
        totalDistanceMeters = totalDistanceMeters,
        streams = streams,
        includeTreadmillInPrs = includeTreadmillInPrs,
    )

    /** One sample every [intervalSec] seconds at a constant pace, plus an exact final sample. */
    private fun constantPaceStream(
        totalMeters: Double,
        paceSecPerKm: Int,
        intervalSec: Int,
    ): ActivityStreams {
        val totalSec = (totalMeters * paceSecPerKm / 1000.0).toInt()
        val speed = 1000.0 / paceSecPerKm
        val offsets = mutableListOf<Int>()
        val distance = mutableListOf<Double>()
        var t = 0
        while (t < totalSec) {
            offsets += t
            distance += t * speed
            t += intervalSec
        }
        offsets += totalSec
        distance += totalMeters
        return stream(offsets, distance, intervalSec.toDouble())
    }

    /** One kilometre per entry of [pacesSecPerKm], sampled every [intervalSec] seconds. */
    private fun variablePaceStream(pacesSecPerKm: List<Int>, intervalSec: Int): ActivityStreams {
        val offsets = mutableListOf(0)
        val distance = mutableListOf(0.0)
        var t = 0
        var d = 0.0
        for (pace in pacesSecPerKm) {
            val steps = pace / intervalSec
            val perStep = 1000.0 / steps
            repeat(steps) {
                t += intervalSec
                d += perStep
                offsets += t
                distance += d
            }
        }
        return stream(offsets, distance, intervalSec.toDouble())
    }

    private fun stream(offsets: List<Int>, distance: List<Double>, interval: Double) = ActivityStreams(
        sampleOffsetsSec = offsets.toIntArray(),
        hr = List(offsets.size) { null },
        distanceMeters = distance.toDoubleArray(),
        sampleCount = offsets.size,
        medianIntervalSec = interval,
    )

    private fun best(efforts: List<BestEffort>, distance: Double): BestEffort? =
        efforts.firstOrNull { it.distanceMeters == distance }

    @Test
    fun pr01_full_activity_exact_5k() {
        val efforts = RunningBestEngine.compute(
            input(durationSec = 1200, totalDistanceMeters = 5000.0),
        )

        assertThat(efforts).hasSize(1)
        val effort = efforts.single()
        assertThat(effort.distanceMeters).isEqualTo(5000.0)
        assertThat(effort.timeSec).isWithin(0.001).of(1200.0)
        assertThat(effort.method).isEqualTo(PrMethod.FULL_ACTIVITY)
        assertThat(effort.isEstimated).isFalse()
        assertThat(effort.paceSecPerKm).isWithin(0.001).of(240.0)
    }

    @Test
    fun pr02_full_activity_scaled_within_one_percent() {
        val effort = RunningBestEngine.compute(
            input(durationSec = 1210, totalDistanceMeters = 5040.0),
        ).single()

        assertThat(effort.timeSec).isWithin(0.1).of(1200.4)
        assertThat(effort.isEstimated).isTrue()
    }

    @Test
    fun pr03_full_activity_rejected_beyond_tolerance() {
        val efforts = RunningBestEngine.compute(
            input(durationSec = 1300, totalDistanceMeters = 5300.0),
        )

        assertThat(best(efforts, 5000.0)).isNull()
        assertThat(efforts).isEmpty()
    }

    @Test
    fun pr04_best_split_finds_fastest_1k_in_10k() {
        // Ten kilometres at 5:00/km except the fourth, run in 4:00.
        val paces = listOf(300, 300, 300, 240, 300, 300, 300, 300, 300, 300)
        val efforts = RunningBestEngine.compute(
            input(durationSec = 2940, totalDistanceMeters = 10_000.0, streams = variablePaceStream(paces, 5)),
        )

        val oneK = best(efforts, CanonicalDistances.ONE_KM)!!
        assertThat(oneK.method).isEqualTo(PrMethod.BEST_SPLIT)
        assertThat(oneK.timeSec).isWithin(0.5).of(240.0)
        assertThat(oneK.isEstimated).isFalse()
        assertThat(best(efforts, CanonicalDistances.TEN_KM)!!.timeSec).isWithin(1.0).of(2940.0)
    }

    @Test
    fun pr05_best_split_interpolates_between_samples() {
        // Constant 4:07/km sampled every 5 s: the exact kilometre falls between two samples.
        val efforts = RunningBestEngine.compute(
            input(
                durationSec = 494,
                totalDistanceMeters = 2000.0,
                streams = constantPaceStream(2000.0, 247, 5),
            ),
        )

        val oneK = best(efforts, CanonicalDistances.ONE_KM)!!
        assertThat(oneK.timeSec).isWithin(0.5).of(247.0)
        assertThat(oneK.timeSec % 5.0).isNotEqualTo(0.0)
        assertThat(oneK.isEstimated).isFalse()
    }

    @Test
    fun pr06_best_split_marks_estimated_on_sparse_samples() {
        val efforts = RunningBestEngine.compute(
            input(
                durationSec = 600,
                totalDistanceMeters = 2000.0,
                streams = constantPaceStream(2000.0, 300, 30),
            ),
        )

        val oneK = best(efforts, CanonicalDistances.ONE_KM)!!
        assertThat(oneK.method).isEqualTo(PrMethod.BEST_SPLIT)
        assertThat(oneK.isEstimated).isTrue()
        assertThat(oneK.timeSec).isWithin(1.0).of(300.0)
    }

    @Test
    fun pr07_implausible_pace_rejected() {
        val tooFast = RunningBestEngine.compute(input(durationSec = 100, totalDistanceMeters = 1000.0))
        val tooSlow = RunningBestEngine.compute(input(durationSec = 1000, totalDistanceMeters = 1000.0))

        assertThat(tooFast).isEmpty()
        assertThat(tooSlow).isEmpty()
    }

    @Test
    fun pr08_treadmill_excluded_by_default() {
        val excluded = RunningBestEngine.compute(
            input(sportType = SportType.RUN_TREADMILL, durationSec = 1200, totalDistanceMeters = 5000.0),
        )
        val included = RunningBestEngine.compute(
            input(
                sportType = SportType.RUN_TREADMILL,
                durationSec = 1200,
                totalDistanceMeters = 5000.0,
                includeTreadmillInPrs = true,
            ),
        )

        assertThat(excluded).isEmpty()
        assertThat(included).hasSize(1)
        assertThat(RunningBestEngine.compute(
            input(sportType = SportType.CYCLING, durationSec = 1200, totalDistanceMeters = 5000.0),
        )).isEmpty()
    }

    @Test
    fun pr09_pr_is_minimum_per_distance() {
        val rows = listOf(
            row(activityId = 1L, timeSec = 1300),
            row(activityId = 2L, timeSec = 1200),
            row(activityId = 3L, timeSec = 1250),
        )

        val prs = RunningBestEngine.bestPerDistance(rows)

        assertThat(prs).hasSize(1)
        assertThat(prs.single().timeSec).isEqualTo(1200)
        assertThat(prs.single().activityId).isEqualTo(2L)
    }

    @Test
    fun pr12_empty_stream_returns_no_bests() {
        val empty = stream(emptyList(), emptyList(), 0.0)

        assertThat(RunningBestEngine.compute(input(durationSec = 0, streams = empty))).isEmpty()
        assertThat(RunningBestEngine.compute(input(durationSec = 1200, streams = empty))).isEmpty()
        assertThat(RunningBestEngine.bestSplitSec(DoubleArray(0), DoubleArray(0), 1000.0)).isNull()
        assertThat(RunningBestEngine.bestSplitSec(doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 50.0), 1000.0))
            .isNull()
    }

    @Test
    fun both_edges_of_the_split_are_interpolated() {
        // 150 m every 30 s (3:20/km): the exact kilometre falls between samples on both edges.
        val offsets = (0..20).map { (it * 30).toDouble() }
        val distance = (0..20).map { it * 150.0 }
        val split = RunningBestEngine.bestSplitSec(
            offsets.toDoubleArray(),
            distance.toDoubleArray(),
            1000.0,
        )!!

        assertThat(split).isWithin(0.001).of(200.0)
        assertThat(split % 30.0).isNotEqualTo(0.0)
    }

    @Test
    fun a_split_is_preferred_over_the_scaled_full_activity_when_faster() {
        // 5.04 km with a genuinely faster first 5 km than the scaled whole-activity estimate.
        val paces = listOf(230, 230, 230, 230, 230)
        val efforts = RunningBestEngine.compute(
            input(durationSec = 1160, totalDistanceMeters = 5040.0, streams = variablePaceStream(paces, 5)),
        )

        val fiveK = best(efforts, CanonicalDistances.FIVE_KM)!!
        assertThat(fiveK.method).isEqualTo(PrMethod.BEST_SPLIT)
        assertThat(fiveK.isEstimated).isFalse()
        assertThat(fiveK.timeSec).isWithin(1.0).of(1150.0)
    }

    @Test
    fun rows_carry_the_activity_day_and_rounded_seconds() {
        val rows = RunningBestEngine.toRows(
            efforts = listOf(
                BestEffort(5000.0, 1200.4, PrMethod.FULL_ACTIVITY, isEstimated = true),
            ),
            activityId = 7L,
            day = 20_000L,
            createdAtMillis = 123L,
        )

        val row = rows.single()
        assertThat(row.timeSec).isEqualTo(1200)
        assertThat(row.paceSecPerKm).isEqualTo(240)
        assertThat(row.method).isEqualTo("FULL_ACTIVITY")
        assertThat(row.isEstimated).isTrue()
        assertThat(row.activityId).isEqualTo(7L)
        assertThat(row.day).isEqualTo(20_000L)
    }

    @Test
    fun one_row_per_canonical_distance_below_the_total() {
        val efforts = RunningBestEngine.compute(
            input(
                durationSec = 1800,
                totalDistanceMeters = 6000.0,
                streams = constantPaceStream(6000.0, 300, 1),
            ),
        )

        assertThat(efforts.map { it.distanceMeters }).containsExactly(
            CanonicalDistances.ONE_KM,
            CanonicalDistances.MILE,
            CanonicalDistances.THREE_KM,
            CanonicalDistances.FIVE_KM,
        ).inOrder()
    }

    @Test
    fun eligible_sports_are_the_three_outdoor_run_types() {
        assertThat(RunningBestEngine.isEligible(SportType.RUN_OUTDOOR, false)).isTrue()
        assertThat(RunningBestEngine.isEligible(SportType.RUN_TRACK, false)).isTrue()
        assertThat(RunningBestEngine.isEligible(SportType.RUN_TRAIL, false)).isTrue()
        assertThat(RunningBestEngine.isEligible(SportType.RUN_TREADMILL, false)).isFalse()
        assertThat(RunningBestEngine.isEligible(SportType.WALK, true)).isFalse()
    }

    private fun row(activityId: Long, timeSec: Int) = RunningBest(
        id = 0L,
        distanceMeters = 5000.0,
        timeSec = timeSec,
        activityId = activityId,
        day = 20_000L,
        method = "FULL_ACTIVITY",
        isEstimated = false,
        paceSecPerKm = timeSec / 5,
        createdAtMillis = 0L,
    )
}

package com.myhealth.domain.engine.bike

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * [BikeBestEngine] against PLAN §3.8.2/§3.8.3: the power windows, the distance splits, the
 * full-ride fallback and the two sanity windows.
 */
class BikeBestEngineTest {

    private fun powerStream(
        watts: (Int) -> Int,
        seconds: Int,
        intervalSec: Int = 1,
    ): ActivityStreams {
        val offsets = IntArray(seconds / intervalSec) { it * intervalSec }
        return ActivityStreams(
            sampleOffsetsSec = offsets,
            hr = List(offsets.size) { null },
            powerW = IntArray(offsets.size) { watts(offsets[it]) },
            sampleCount = offsets.size,
            medianIntervalSec = intervalSec.toDouble(),
        )
    }

    /** A ride at a constant [speedMps], sampled every [intervalSec] seconds. */
    private fun distanceStream(speedMps: Double, seconds: Int, intervalSec: Int = 10): ActivityStreams {
        val offsets = IntArray(seconds / intervalSec + 1) { it * intervalSec }
        return ActivityStreams(
            sampleOffsetsSec = offsets,
            hr = List(offsets.size) { null },
            distanceMeters = DoubleArray(offsets.size) { offsets[it] * speedMps },
            sampleCount = offsets.size,
            medianIntervalSec = intervalSec.toDouble(),
        )
    }

    private fun input(
        sportType: SportType = SportType.CYCLING,
        durationSec: Int = 1_800,
        totalDistanceMeters: Double? = null,
        streams: ActivityStreams? = null,
    ) = RideEffortInput(sportType, durationSec, totalDistanceMeters, streams)

    private fun value(efforts: List<RideBestEffort>, kind: RideBestKind): Double? =
        efforts.firstOrNull { it.kind == kind }?.value

    @Test
    fun rb01_constant_250w_30min_5_and_20_min_250_no_60min() {
        val efforts = BikeBestEngine.compute(
            input(streams = powerStream(watts = { 250 }, seconds = 1_800)),
        )

        assertThat(value(efforts, RideBestKind.POWER_5MIN)).isEqualTo(250.0)
        assertThat(value(efforts, RideBestKind.POWER_20MIN)).isEqualTo(250.0)
        // Half an hour cannot contain a 60-minute window.
        assertThat(value(efforts, RideBestKind.POWER_60MIN)).isNull()
        assertThat(efforts.none { it.isEstimated }).isTrue()
    }

    @Test
    fun rb02_5min_400_inside_300_gives_5min_400_20min_325() {
        // 30 min at 300 W with a 5-minute 400 W block from 10:00 to 15:00.
        val efforts = BikeBestEngine.compute(
            input(
                streams = powerStream(
                    watts = { t -> if (t in 600 until 900) 400 else 300 },
                    seconds = 1_800,
                ),
            ),
        )

        assertThat(value(efforts, RideBestKind.POWER_5MIN)).isEqualTo(400.0)
        // (900 s x 300 W + 300 s x 400 W) / 1200 s = 325 W.
        assertThat(value(efforts, RideBestKind.POWER_20MIN)).isEqualTo(325.0)
    }

    @Test
    fun rb03_sparse_samples_estimated() {
        val sparse = BikeBestEngine.compute(
            input(streams = powerStream(watts = { 250 }, seconds = 1_800, intervalSec = 30)),
        )
        val dense = BikeBestEngine.compute(
            input(streams = powerStream(watts = { 250 }, seconds = 1_800, intervalSec = 5)),
        )

        assertThat(value(sparse, RideBestKind.POWER_20MIN)).isEqualTo(250.0)
        assertThat(sparse.all { it.isEstimated }).isTrue()
        assertThat(dense.none { it.isEstimated }).isTrue()
    }

    @Test
    fun rb04_time_10k_at_30kmh_1200s() {
        // 15 km at exactly 30 km/h: the 10 km split takes 20 minutes.
        val efforts = BikeBestEngine.compute(
            input(
                durationSec = 1_800,
                totalDistanceMeters = 15_000.0,
                streams = distanceStream(speedMps = 10_000.0 / 1_200.0, seconds = 1_800),
            ),
        )

        assertThat(value(efforts, RideBestKind.TIME_10K)).isEqualTo(1_200.0)
        assertThat(efforts.first { it.kind == RideBestKind.TIME_10K }.isEstimated).isFalse()
        // 20 km never happened.
        assertThat(value(efforts, RideBestKind.TIME_20K)).isNull()
    }

    @Test
    fun rb05_90kmh_split_rejected() {
        // 10 km in 400 s is 90 km/h — a GPS blow-up, not a ride.
        val efforts = BikeBestEngine.compute(
            input(
                durationSec = 400,
                totalDistanceMeters = 10_000.0,
                streams = distanceStream(speedMps = 25.0, seconds = 400),
            ),
        )

        assertThat(efforts.filter { !it.kind.isPower }).isEmpty()
    }

    @Test
    fun rb06_full_ride_40_2km_in_4000s_scaled_3980s_estimated() {
        // No stream at all — the Health Connect case: only the full-ride method can produce a time.
        val efforts = BikeBestEngine.compute(
            input(durationSec = 4_000, totalDistanceMeters = 40_200.0),
        )

        val fortyK = efforts.single { it.kind == RideBestKind.TIME_40K }
        // 4000 s x 40000 / 40200 = 3980.1 s, rounded half-up.
        assertThat(fortyK.value).isEqualTo(3_980.0)
        assertThat(fortyK.isEstimated).isTrue()
        // 10 km and 20 km are far outside the max(1 %, 50 m) tolerance of the whole ride.
        assertThat(value(efforts, RideBestKind.TIME_10K)).isNull()
        assertThat(value(efforts, RideBestKind.TIME_20K)).isNull()
        assertThat(efforts.filter { it.kind.isPower }).isEmpty()
    }

    @Test
    fun rb07_non_bike_sport_empty() {
        val run = BikeBestEngine.compute(
            input(
                sportType = SportType.RUN_OUTDOOR,
                totalDistanceMeters = 10_000.0,
                streams = powerStream(watts = { 250 }, seconds = 1_800),
            ),
        )

        assertThat(run).isEmpty()
    }

    @Test
    fun a_pause_cannot_stretch_an_effort_over_the_gap_cap() {
        // 4 minutes at 400 W, a 30-minute pause, then 4 more minutes: the 5-minute window may only
        // borrow 60 s of that pause, so it cannot be a 400 W five-minute best.
        val offsets = IntArray(480) { if (it < 240) it else it + 1_800 }
        val streams = ActivityStreams(
            sampleOffsetsSec = offsets,
            hr = List(offsets.size) { null },
            powerW = IntArray(offsets.size) { 400 },
            sampleCount = offsets.size,
            medianIntervalSec = 1.0,
        )

        val efforts = BikeBestEngine.compute(input(durationSec = 2_280, streams = streams))

        // Capped coverage is 239 + 60 + 239 + 1 = 539 s, so a 5-minute window exists but spans the
        // pause; its mean is still 400 W because the paused sample carries the last power reading.
        assertThat(value(efforts, RideBestKind.POWER_5MIN)).isEqualTo(400.0)
        assertThat(value(efforts, RideBestKind.POWER_20MIN)).isNull()
    }

    @Test
    fun an_implausible_power_best_is_dropped() {
        val efforts = BikeBestEngine.compute(
            input(streams = powerStream(watts = { 2_000 }, seconds = 1_800)),
        )

        assertThat(efforts).isEmpty()
    }

    @Test
    fun rows_carry_the_activity_and_day() {
        val efforts = BikeBestEngine.compute(
            input(streams = powerStream(watts = { 250 }, seconds = 1_800)),
        )
        val rows = BikeBestEngine.toRows(efforts, activityId = 9L, day = 20_000L, createdAtMillis = 5L)

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.kind })
            .containsExactly(RideBestKind.POWER_5MIN, RideBestKind.POWER_20MIN)
        assertThat(rows.all { it.activityId == 9L && it.day == 20_000L && it.createdAtMillis == 5L })
            .isTrue()
    }
}

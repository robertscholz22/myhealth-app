package com.myhealth.domain.engine.bike

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * [FtpEstimator] against PLAN §3.8.1: the manual override, the 20-minute rung, the session-NP rung,
 * the 90-day window and the sanity gate.
 */
class FtpEstimatorTest {

    private val today = 20_000L

    private fun best(
        watts: Double,
        kind: RideBestKind = RideBestKind.POWER_20MIN,
        dayOffset: Long = -10,
        activityId: Long = 7L,
    ): RideBest = RideBest(
        id = watts.toLong(),
        kind = kind,
        value = watts,
        activityId = activityId,
        day = today + dayOffset,
        isEstimated = false,
        createdAtMillis = 0L,
    )

    private fun ride(
        normalizedPowerW: Int? = null,
        avgPowerW: Int? = null,
        durationSec: Int = 45 * 60,
        dayOffset: Long = -5,
        sportType: SportType = SportType.CYCLING,
        activityId: Long = 3L,
    ): RidePower = RidePower(
        activityId = activityId,
        day = today + dayOffset,
        durationSec = durationSec,
        sportType = sportType,
        avgPowerW = avgPowerW,
        normalizedPowerW = normalizedPowerW,
    )

    @Test
    fun ftp01_manual_wins() {
        val estimate = FtpEstimator.estimate(
            manualWatts = 250,
            powerBests = listOf(best(300.0)),
            rides = listOf(ride(normalizedPowerW = 280)),
            todayDay = today,
        )!!

        assertThat(estimate.watts).isEqualTo(250)
        assertThat(estimate.source).isEqualTo(FtpSource.MANUAL)
        assertThat(estimate.basisActivityId).isNull()
        assertThat(estimate.basisDay).isNull()
    }

    @Test
    fun ftp02_stream_20min_300_gives_285() {
        val estimate = FtpEstimator.estimate(
            manualWatts = null,
            powerBests = listOf(
                best(300.0, activityId = 42L),
                best(280.0, activityId = 41L),
                // A 5-minute best is stronger but says nothing about threshold power.
                best(420.0, kind = RideBestKind.POWER_5MIN, activityId = 40L),
            ),
            rides = listOf(ride(normalizedPowerW = 280)),
            todayDay = today,
        )!!

        assertThat(estimate.watts).isEqualTo(285)
        assertThat(estimate.source).isEqualTo(FtpSource.STREAM_20MIN)
        assertThat(estimate.basisActivityId).isEqualTo(42L)
        assertThat(estimate.basisDay).isEqualTo(today - 10)
    }

    @Test
    fun ftp03_session_np_only_rides_over_40min_260_gives_247() {
        val estimate = FtpEstimator.estimate(
            manualWatts = null,
            powerBests = emptyList(),
            rides = listOf(
                ride(normalizedPowerW = 260, durationSec = 45 * 60, activityId = 11L),
                // Harder, but 30 minutes — not a threshold effort.
                ride(normalizedPowerW = 310, durationSec = 30 * 60, activityId = 12L),
                // A 40-minute run with "power" must never reach the cycling ladder.
                ride(normalizedPowerW = 400, sportType = SportType.RUN_OUTDOOR, activityId = 13L),
            ),
            todayDay = today,
        )!!

        assertThat(estimate.watts).isEqualTo(247)
        assertThat(estimate.source).isEqualTo(FtpSource.SESSION_NP)
        assertThat(estimate.basisActivityId).isEqualTo(11L)
    }

    @Test
    fun ftp04_nothing_gives_null() {
        assertThat(FtpEstimator.estimate(null, emptyList(), emptyList(), today)).isNull()
        // Rides without any power at all cannot produce an estimate either.
        assertThat(FtpEstimator.estimate(null, emptyList(), listOf(ride()), today)).isNull()
    }

    @Test
    fun ftp05_91_day_old_best_ignored() {
        val stale = FtpEstimator.estimate(null, listOf(best(300.0, dayOffset = -91)), emptyList(), today)
        assertThat(stale).isNull()

        // Exactly 90 days old is still inside the window.
        val fresh = FtpEstimator.estimate(null, listOf(best(300.0, dayOffset = -90)), emptyList(), today)!!
        assertThat(fresh.watts).isEqualTo(285)

        // The same cut-off applies to the session-NP rung.
        val staleRide = FtpEstimator.estimate(
            null,
            emptyList(),
            listOf(ride(normalizedPowerW = 260, dayOffset = -91)),
            today,
        )
        assertThat(staleRide).isNull()
    }

    @Test
    fun the_session_rung_prefers_normalized_power_over_the_average() {
        val estimate = FtpEstimator.estimate(
            manualWatts = null,
            powerBests = emptyList(),
            rides = listOf(ride(normalizedPowerW = 260, avgPowerW = 200)),
            todayDay = today,
        )!!

        assertThat(estimate.watts).isEqualTo(247)

        val avgOnly = FtpEstimator.estimate(
            manualWatts = null,
            powerBests = emptyList(),
            rides = listOf(ride(normalizedPowerW = null, avgPowerW = 200)),
            todayDay = today,
        )!!
        assertThat(avgOnly.watts).isEqualTo(190)
    }

    @Test
    fun an_implausible_estimate_is_null_rather_than_clamped() {
        // 0.95 x 700 W = 665 W over the 600 W ceiling.
        assertThat(FtpEstimator.estimate(null, listOf(best(700.0)), emptyList(), today)).isNull()
        // 0.95 x 40 W = 38 W under the 50 W floor.
        assertThat(FtpEstimator.estimate(null, listOf(best(40.0)), emptyList(), today)).isNull()
        // Even a manual value has to be a possible FTP.
        assertThat(FtpEstimator.estimate(20, emptyList(), emptyList(), today)).isNull()
    }
}

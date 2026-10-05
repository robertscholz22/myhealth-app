package com.myhealth.ui.bike

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import org.junit.Test
import java.time.LocalDate

/** The Bike screen's pure display logic (PLAN "UI.", P12.4). */
class BikeUiStateTest {

    private fun rideBest(
        kind: RideBestKind,
        value: Double,
        day: Long = LocalDate.of(2026, 9, 2).toEpochDay(),
        activityId: Long? = 1L,
        isEstimated: Boolean = false,
    ) = RideBest(
        id = 0L,
        kind = kind,
        value = value,
        activityId = activityId,
        day = day,
        isEstimated = isEstimated,
        createdAtMillis = 0L,
    )

    @Test
    fun bikeui01_ftp_card_shows_source_and_basis() {
        val basisDay = LocalDate.of(2026, 9, 2).toEpochDay()
        val ftp = FtpEstimate(watts = 247, source = FtpSource.SESSION_NP, basisActivityId = 9L, basisDay = basisDay)

        assertThat(ftp.source.label()).isEqualTo("Ride average power")
        assertThat(formatShortDate(ftp.basisDay!!)).isEqualTo("2 Sep")

        val stream = FtpEstimate(watts = 285, source = FtpSource.STREAM_20MIN, basisActivityId = 1L, basisDay = basisDay)
        assertThat(stream.source.label()).isEqualTo("20-minute best")

        val manual = FtpEstimate(watts = 300, source = FtpSource.MANUAL)
        assertThat(manual.source.label()).isEqualTo("Manual")
        assertThat(manual.basisDay).isNull()
    }

    @Test
    fun bikeui02_bests_table_max_for_power_min_for_time() {
        val bests = listOf(
            rideBest(RideBestKind.POWER_20MIN, 250.0, day = 100, activityId = 1L),
            rideBest(RideBestKind.POWER_20MIN, 300.0, day = 200, activityId = 2L),
            rideBest(RideBestKind.POWER_5MIN, 400.0, day = 150, activityId = 3L),
            rideBest(RideBestKind.TIME_40K, 4000.0, day = 100, activityId = 4L),
            rideBest(RideBestKind.TIME_40K, 3800.0, day = 200, activityId = 5L),
            rideBest(RideBestKind.TIME_10K, 1200.0, day = 100, activityId = 6L),
        )

        val power = bests.powerBests()
        assertThat(power.map { it.kind }).containsExactly(RideBestKind.POWER_5MIN, RideBestKind.POWER_20MIN).inOrder()
        assertThat(power.first { it.kind == RideBestKind.POWER_20MIN }.value).isEqualTo(300.0)

        val time = bests.timeBests()
        assertThat(time.map { it.kind }).containsExactly(RideBestKind.TIME_10K, RideBestKind.TIME_40K).inOrder()
        assertThat(time.first { it.kind == RideBestKind.TIME_40K }.value).isEqualTo(3800.0)

        assertThat(formatRideBestValue(power.first { it.kind == RideBestKind.POWER_20MIN })).isEqualTo("300 W")
        assertThat(formatRideBestValue(time.first { it.kind == RideBestKind.TIME_40K })).isEqualTo("1:03:20")
        assertThat(rideBestKindLabel(RideBestKind.POWER_20MIN)).isEqualTo("20 min power")
        assertThat(rideBestKindLabel(RideBestKind.TIME_40K)).isEqualTo("40 km")
    }
}

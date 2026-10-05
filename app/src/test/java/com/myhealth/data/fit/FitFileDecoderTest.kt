package com.myhealth.data.fit

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.datetime.TimeZone
import org.junit.Test

/**
 * Exercises the shared FIT decoder (P20.2; the Garmin SDK until then) against the binary fixture
 * built by [RunFixtureEncoder] (PLAN P7.1/P7.2).
 */
class FitFileDecoderTest {

    private val decoder = FitFileDecoder()

    private fun decodeFixture(): FitFileData {
        val bytes = RunFixtureEncoder.ensure().readBytes()
        return when (val outcome = decoder.decode(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("Decoding run_5k.fit failed: ${outcome.error}")
        }
    }

    @Test
    fun decodes_the_5k_run_fixture_into_sessions_laps_and_records() {
        val data = decodeFixture()

        val session = data.sessions.single()
        assertThat(session.sport).isEqualTo("RUNNING")
        assertThat(session.subSport).isEqualTo("STREET")
        assertThat(checkNotNull(session.totalDistanceMeters)).isWithin(1.0).of(5_000.0)
        assertThat(checkNotNull(session.totalTimerSec)).isWithin(0.5).of(1_500.0)
        assertThat(session.startAtMillis).isEqualTo(RunFixtureEncoder.START_MILLIS)
        assertThat(session.avgHr).isEqualTo(152)

        assertThat(data.records.size).isAtLeast(1_490)
        assertThat(data.records.size).isAtMost(1_510)
        assertThat(data.laps).hasSize(2)
        assertThat(data.localTimestampOffsetSec).isEqualTo(7_200L)

        val fileId = checkNotNull(data.fileId)
        assertThat(fileId.type).isEqualTo("ACTIVITY")
        assertThat(fileId.serialNumber).isEqualTo(3_912_345_678L)
    }

    @Test
    fun record_channels_survive_the_round_trip() {
        val data = decodeFixture()

        val first = data.records.first()
        assertThat(first.timestampMillis).isEqualTo(RunFixtureEncoder.START_MILLIS)
        assertThat(first.hr).isEqualTo(130)
        assertThat(checkNotNull(first.speedMps)).isWithin(0.01).of(3.3333)
        assertThat(checkNotNull(first.positionLatSemicircles)).isNotEqualTo(0)

        val last = data.records.last()
        assertThat(checkNotNull(last.distanceMeters)).isWithin(1.0).of(5_000.0)
    }

    @Test
    fun the_decoded_fixture_maps_to_one_5k_running_activity() {
        val items = FitToDomainMapper().toIngestItems(
            decodeFixture(),
            TimeZone.of("Europe/Berlin"),
            nowMillis = 1_800_000_000_000L,
        )

        val session = items.single().session
        assertThat(session.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(checkNotNull(session.distanceMeters)).isWithin(1.0).of(5_000.0)
        assertThat(session.durationSec).isEqualTo(1_500)
        assertThat(session.laps).hasSize(2)
        val streams = checkNotNull(session.streams)
        assertThat(streams.sampleCount).isAtLeast(1_490)
        assertThat(streams.hr.none { it == null }).isTrue()
        assertThat(checkNotNull(streams.latLngE7)).hasSize(streams.sampleCount)
    }

    /**
     * P12: the binary `ride_power.fit` fixture — a 60-minute trainer ride at 220 W with a
     * 20-minute 300 W block and no heart rate — survives the real SDK decoder, and the mapper
     * turns it into session power fields plus a `powerW` stream.
     */
    @Test
    fun bike05_fit_power_stream_and_session_fields() {
        val bytes = RidePowerFixtureEncoder.ensure().readBytes()
        val data = when (val outcome = decoder.decode(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("Decoding ride_power.fit failed: ${outcome.error}")
        }

        // The decoder lifted session.avg_power / max_power / normalized_power…
        val decoded = data.sessions.single()
        assertThat(decoded.avgPowerW).isEqualTo(RidePowerFixtureEncoder.AVG_POWER_W)
        assertThat(decoded.maxPowerW).isEqualTo(RidePowerFixtureEncoder.BLOCK_WATTS)
        assertThat(decoded.normalizedPowerW).isEqualTo(RidePowerFixtureEncoder.NORMALIZED_POWER_W)
        // …and record.power.
        assertThat(data.records.first().powerW).isEqualTo(RidePowerFixtureEncoder.BASE_WATTS)
        assertThat(data.records.none { it.hr != null }).isTrue()

        val session = FitToDomainMapper().toIngestItems(
            data,
            TimeZone.of("Europe/Berlin"),
            nowMillis = 1_800_000_000_000L,
        ).single().session

        assertThat(session.sportType).isEqualTo(SportType.CYCLING_INDOOR)
        assertThat(session.avgPowerW).isEqualTo(RidePowerFixtureEncoder.AVG_POWER_W)
        assertThat(session.maxPowerW).isEqualTo(RidePowerFixtureEncoder.BLOCK_WATTS)
        assertThat(session.normalizedPowerW).isEqualTo(RidePowerFixtureEncoder.NORMALIZED_POWER_W)
        // Cycling cadence passes through as rpm — it is not doubled the way a run's would be.
        assertThat(checkNotNull(session.avgCadenceSpm))
            .isWithin(0.5).of(RidePowerFixtureEncoder.CADENCE_RPM.toDouble())

        val power = checkNotNull(checkNotNull(session.streams).powerW)
        assertThat(power).hasLength(session.streams!!.sampleCount)
        assertThat(power.first()).isEqualTo(RidePowerFixtureEncoder.BASE_WATTS)
        assertThat(power[RidePowerFixtureEncoder.BLOCK_START_SEC + 10])
            .isEqualTo(RidePowerFixtureEncoder.BLOCK_WATTS)
        assertThat(power[RidePowerFixtureEncoder.BLOCK_END_SEC + 10])
            .isEqualTo(RidePowerFixtureEncoder.BASE_WATTS)
        assertThat(power.max()).isEqualTo(RidePowerFixtureEncoder.BLOCK_WATTS)
    }

    @Test
    fun a_stream_that_is_not_a_fit_file_becomes_a_parse_error() {
        val outcome = decoder.decode(ByteArray(64) { 0x7 })

        assertThat(outcome).isInstanceOf(Outcome.Err::class.java)
        assertThat((outcome as Outcome.Err).error).isInstanceOf(AppError.Parse::class.java)
    }
}

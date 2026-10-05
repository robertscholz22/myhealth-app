package com.myhealth.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.SportType
import kotlinx.datetime.TimeZone
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The P12 half of `HcMapperTest`: cycling power and pedalling cadence through `HcMapper`
 * (`bike03`, `bike04`). Split into its own file so both stay inside rule R10; like its sibling it
 * runs on the JVM against the plain DTOs of `HcDto.kt`, with no Health Connect client involved.
 */
class HcMapperPowerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val tz: TimeZone = TimeZone.of("Europe/Berlin")
    private val mapper = HealthConnectMapper()

    private fun at(iso: String): Long =
        LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private fun rideOf(
        start: String,
        end: String,
        type: Int,
        powerSamples: List<HcSample> = emptyList(),
        pedalCadenceSamples: List<HcSample> = emptyList(),
        heartRateSamples: List<HcHeartRateSample> = emptyList(),
    ) = HcExercise(
        externalId = "hc-ride",
        packageName = "com.garmin.android.apps.connectmobile",
        startMillis = at(start),
        endMillis = at(end),
        exerciseType = type,
        title = null,
        notes = null,
        heartRateSamples = heartRateSamples,
        powerSamples = powerSamples,
        pedalCadenceSamples = pedalCadenceSamples,
    )

    /** `count` samples starting at [start], one every [everySec] seconds, valued by [value]. */
    private fun samples(
        start: String,
        count: Int,
        everySec: Int = 1,
        value: (Int) -> Double,
    ): List<HcSample> = List(count) { index ->
        HcSample(at(start) + index * everySec * 1000L, value(index))
    }

    /**
     * P12: heart rate is the axis, and power — which Health Connect writes on its own 5-second
     * clock — is resampled onto it last-value-carried-forward, exactly like speed and cadence.
     */
    @Test
    fun bike03_hc_power_samples_resampled_onto_hr_axis() {
        val ride = rideOf(
            start = "2026-02-05T16:00:00",
            end = "2026-02-05T16:01:00",
            type = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            // 60 HR samples, one per second.
            heartRateSamples = List(60) { index ->
                HcHeartRateSample(at("2026-02-05T16:00:00") + index * 1_000L, 140 + index % 3)
            },
            // 12 power samples, one every 5 s: 220 W, then 300 W from the 30-second mark.
            powerSamples = samples("2026-02-05T16:00:00", count = 12, everySec = 5) { index ->
                if (index < 6) 220.0 else 300.0
            },
            pedalCadenceSamples = samples("2026-02-05T16:00:00", count = 12, everySec = 5) { 90.0 },
        )

        val session = mapper.toSession(ride, tz, nowMillis = 1_000L)
        val streams = checkNotNull(session.streams)

        assertThat(streams.sampleCount).isEqualTo(60)
        val power = checkNotNull(streams.powerW)
        assertThat(power).hasLength(60)
        // Carried forward between the 5-second power samples…
        assertThat(power[3]).isEqualTo(220)
        assertThat(power[29]).isEqualTo(220)
        // …and stepping up at the sample that actually changed.
        assertThat(power[30]).isEqualTo(300)
        assertThat(power[59]).isEqualTo(300)

        // A ride's cadence channel is pedalling cadence in rpm (§2.2.2 KDoc).
        assertThat(checkNotNull(streams.cadenceSpm)[10]).isWithin(1e-9).of(90.0)
        assertThat(checkNotNull(session.avgCadenceSpm)).isWithin(1e-9).of(90.0)

        assertThat(session.maxPowerW).isEqualTo(300)
        assertThat(checkNotNull(session.avgPowerW)).isIn(255..265)
        // 55 s of samples is more than the 30-second window, so NP exists and exceeds the average.
        assertThat(checkNotNull(session.normalizedPowerW))
            .isAtLeast(checkNotNull(session.avgPowerW))
    }

    /**
     * P12: a trainer ride recorded without a strap has neither heart rate nor speed. The axis
     * falls back to the power samples (`hr ?: speed ?: power`) so the ride still carries a stream
     * — without it the power chart and the ride bests would have nothing to read.
     */
    @Test
    fun bike04_hc_power_only_ride_gets_a_stream() {
        val ride = rideOf(
            start = "2026-02-05T16:00:00",
            end = "2026-02-05T17:00:00",
            type = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            powerSamples = samples("2026-02-05T16:00:00", count = 720, everySec = 5) { index ->
                if (index in 240 until 480) 300.0 else 220.0
            },
        )

        val session = mapper.toSession(ride, tz, nowMillis = 1_000L)
        val streams = checkNotNull(session.streams)

        assertThat(session.sportType).isEqualTo(SportType.CYCLING_INDOOR)
        assertThat(session.hasStreams).isTrue()
        assertThat(streams.sampleCount).isEqualTo(720)
        assertThat(streams.hr.all { it == null }).isTrue()
        assertThat(streams.speedMps).isNull()
        assertThat(checkNotNull(streams.powerW).first()).isEqualTo(220)
        assertThat(streams.sampleOffsetsSec.last()).isEqualTo(3_595)

        // 480 samples at 220 W and 240 at 300 W -> 246.67 W, half-up 247.
        assertThat(session.avgPowerW).isEqualTo(247)
        assertThat(session.maxPowerW).isEqualTo(300)
        assertThat(checkNotNull(session.normalizedPowerW)).isIn(250..265)

        // The payload records what was read, so an empty channel is visible in the audit trail.
        val payload = mapper.toSourceRecord(ride, receivedAtMillis = 1L).payloadJson
        assertThat(payload).contains("\"powerSampleCount\":720")
        assertThat(payload).contains("\"pedalCadenceSampleCount\":0")
        assertThat(payload).contains("\"normalizedPowerW\":")
    }

}

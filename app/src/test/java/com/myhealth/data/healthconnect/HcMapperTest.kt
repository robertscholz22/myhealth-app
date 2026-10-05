package com.myhealth.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SleepStage
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import kotlinx.datetime.TimeZone
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * PLAN P2.3. Everything here runs on the JVM: the mapper only ever sees the plain DTOs of
 * `HcDto.kt`, so no Health Connect client, no Android framework and no device is involved.
 * The `EXERCISE_TYPE_*` / `STAGE_TYPE_*` references are `const val`s and are inlined by the
 * compiler — the test asserts the *mapping*, never a literal integer value (risk R3).
 */
class HcMapperTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val tz: TimeZone = TimeZone.of("Europe/Berlin")
    private val mapper = HealthConnectMapper()

    private fun at(iso: String): Long =
        LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private fun exercise(
        externalId: String = "hc-1",
        start: String = "2026-03-01T10:00:00",
        end: String = "2026-03-01T11:00:00",
        type: Int = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        title: String? = null,
        distanceMeters: Double? = null,
        totalEnergyKcal: Double? = null,
        activeEnergyKcal: Double? = null,
        heartRateSamples: List<HcHeartRateSample> = emptyList(),
        speedSamples: List<HcSample> = emptyList(),
        cadenceSamples: List<HcSample> = emptyList(),
        powerSamples: List<HcSample> = emptyList(),
        pedalCadenceSamples: List<HcSample> = emptyList(),
    ) = HcExercise(
        externalId = externalId,
        packageName = "com.garmin.android.apps.connectmobile",
        startMillis = at(start),
        endMillis = at(end),
        exerciseType = type,
        title = title,
        notes = null,
        distanceMeters = distanceMeters,
        totalEnergyKcal = totalEnergyKcal,
        activeEnergyKcal = activeEnergyKcal,
        heartRateSamples = heartRateSamples,
        speedSamples = speedSamples,
        cadenceSamples = cadenceSamples,
        powerSamples = powerSamples,
        pedalCadenceSamples = pedalCadenceSamples,
    )

    @Test
    fun hc01_running_session_maps_to_run_outdoor_with_duration_and_day() {
        val session = mapper.toSession(exercise(), tz, nowMillis = 1_000L)

        assertThat(session.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(session.sportGroup).isEqualTo(SportGroup.RUN)
        assertThat(session.durationSec).isEqualTo(3600)
        assertThat(session.elapsedSec).isEqualTo(3600)
        assertThat(session.day).isEqualTo(java.time.LocalDate.of(2026, 3, 1).toEpochDay())
        assertThat(session.primarySource).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(session.mergedSources).containsExactly(ActivitySource.HEALTH_CONNECT)
    }

    @Test
    fun hc02_unknown_exercise_type_falls_back_to_other() {
        val session = mapper.toSession(exercise(type = 9_999), tz, nowMillis = 0L)

        assertThat(session.sportType).isEqualTo(SportType.OTHER)
        assertThat(session.sportGroup).isEqualTo(SportGroup.OTHER)
    }

    @Test
    fun hc03_soccer_title_spiel_upgrades_to_soccer_match() {
        val type = ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        val training = mapper.toSession(exercise(type = type, title = "Fußball"), tz, 0L)
        val match = mapper.toSession(exercise(type = type, title = "Spiel gegen SV"), tz, 0L)
        val english = mapper.toSession(exercise(type = type, title = "Cup MATCH"), tz, 0L)
        val none = mapper.toSession(exercise(type = type, title = null), tz, 0L)

        assertThat(training.sportType).isEqualTo(SportType.SOCCER_TRAINING)
        assertThat(match.sportType).isEqualTo(SportType.SOCCER_MATCH)
        assertThat(english.sportType).isEqualTo(SportType.SOCCER_MATCH)
        assertThat(none.sportType).isEqualTo(SportType.SOCCER_TRAINING)
    }

    @Test
    fun hc04_treadmill_and_the_rest_of_the_type_table() {
        fun typeOf(type: Int) = ExerciseTypeMap.toSportType(type, title = null)

        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
            .isEqualTo(SportType.RUN_TREADMILL)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
            .isEqualTo(SportType.STRENGTH)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING))
            .isEqualTo(SportType.STRENGTH)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
            .isEqualTo(SportType.CYCLING)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY))
            .isEqualTo(SportType.CYCLING_INDOOR)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
            .isEqualTo(SportType.WALK)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
            .isEqualTo(SportType.HIKE)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL))
            .isEqualTo(SportType.SWIM)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER))
            .isEqualTo(SportType.SWIM)
        assertThat(typeOf(ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING))
            .isEqualTo(SportType.HIIT)
    }

    @Test
    fun hc05_session_crossing_midnight_uses_the_local_day_of_its_start() {
        val session = mapper.toSession(
            exercise(start = "2026-03-01T23:30:00", end = "2026-03-02T00:45:00"),
            tz,
            nowMillis = 0L,
        )

        assertThat(session.day).isEqualTo(java.time.LocalDate.of(2026, 3, 1).toEpochDay())
        assertThat(session.durationSec).isEqualTo(75 * 60)
    }

    @Test
    fun hc06_empty_heart_rate_samples_leave_hr_null_and_no_streams() {
        val session = mapper.toSession(exercise(), tz, nowMillis = 0L)

        assertThat(session.avgHr).isNull()
        assertThat(session.maxHr).isNull()
        assertThat(session.streams).isNull()
        assertThat(session.hasStreams).isFalse()
    }

    @Test
    fun hc07_heart_rate_samples_are_downsampled_to_one_per_second_with_offsets_from_start() {
        val start = at("2026-03-01T10:00:00")
        val session = mapper.toSession(
            exercise(
                heartRateSamples = listOf(
                    HcHeartRateSample(start, 120),
                    HcHeartRateSample(start + 300, 121),
                    HcHeartRateSample(start + 700, 122),
                    HcHeartRateSample(start + 1_000, 130),
                    HcHeartRateSample(start + 1_500, 131),
                    HcHeartRateSample(start + 2_000, 140),
                ),
            ),
            tz,
            nowMillis = 0L,
        )

        val streams = requireNotNull(session.streams)
        assertThat(streams.sampleOffsetsSec.toList()).containsExactly(0, 1, 2).inOrder()
        assertThat(streams.hr).containsExactly(120, 130, 140).inOrder()
        assertThat(streams.sampleCount).isEqualTo(3)
        assertThat(streams.medianIntervalSec).isEqualTo(1.0)
        assertThat(session.hasStreams).isTrue()
        // Averages come from every sample, not just the down-sampled axis: mean(120..140) = 127.3.
        assertThat(session.avgHr).isEqualTo(127)
        assertThat(session.maxHr).isEqualTo(140)
    }

    @Test
    fun hc08_speed_and_cadence_are_resampled_onto_the_heart_rate_axis() {
        val start = at("2026-03-01T10:00:00")
        val session = mapper.toSession(
            exercise(
                heartRateSamples = (0..2).map { HcHeartRateSample(start + it * 1_000L, 130 + it) },
                speedSamples = listOf(HcSample(start, 2.0), HcSample(start + 2_000, 4.0)),
                cadenceSamples = listOf(HcSample(start + 1_000, 170.0)),
            ),
            tz,
            nowMillis = 0L,
        )

        val streams = requireNotNull(session.streams)
        assertThat(streams.speedMps?.toList()).containsExactly(2.0, 2.0, 4.0).inOrder()
        assertThat(streams.cadenceSpm?.toList()).containsExactly(170.0, 170.0, 170.0).inOrder()
        assertThat(session.avgSpeedMps).isEqualTo(3.0)
        assertThat(session.maxSpeedMps).isEqualTo(4.0)
        assertThat(session.avgCadenceSpm).isEqualTo(170.0)
    }

    @Test
    fun hc09_average_speed_falls_back_to_distance_over_duration() {
        val session = mapper.toSession(
            exercise(
                start = "2026-03-01T10:00:00",
                end = "2026-03-01T11:00:00",
                distanceMeters = 10_800.0,
            ),
            tz,
            nowMillis = 0L,
        )

        assertThat(session.avgSpeedMps).isEqualTo(3.0)
        assertThat(session.maxSpeedMps).isNull()
    }

    @Test
    fun hc10_kcal_precedence_keeps_active_and_total_in_their_own_channels() {
        val both = mapper.toSession(
            exercise(totalEnergyKcal = 780.0, activeEnergyKcal = 640.0),
            tz,
            nowMillis = 0L,
        )
        val totalOnly = mapper.toSession(exercise(totalEnergyKcal = 780.0), tz, nowMillis = 0L)

        assertThat(both.totalEnergyKcal).isEqualTo(780.0)
        assertThat(both.activeEnergyKcal).isEqualTo(640.0)
        // Total includes BMR, so it must never be promoted into the active channel.
        assertThat(totalOnly.totalEnergyKcal).isEqualTo(780.0)
        assertThat(totalOnly.activeEnergyKcal).isNull()
    }

    @Test
    fun hc11_source_record_uses_metadata_id_and_a_normalized_payload() {
        val record = mapper.toSourceRecord(
            exercise(externalId = "hc-abc", totalEnergyKcal = 500.0, distanceMeters = 9_000.0),
            receivedAtMillis = 42L,
        )

        assertThat(record.source).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(record.externalId).isEqualTo("hc-abc")
        assertThat(record.activityId).isNull()
        assertThat(record.receivedAtMillis).isEqualTo(42L)
        assertThat(record.payloadJson).contains("\"externalId\":\"hc-abc\"")
        assertThat(record.payloadJson).contains("\"sportType\":\"RUN_OUTDOOR\"")
        assertThat(record.payloadJson).contains("\"totalEnergyKcal\":500.0")
        assertThat(record.payloadJson).contains("\"durationSec\":3600")
    }

    @Test
    fun hc12_dedupe_bucket_is_the_sport_group_and_a_five_minute_slot() {
        val start = at("2026-03-01T10:00:00")
        val session = mapper.toSession(exercise(), tz, nowMillis = 0L)

        assertThat(session.dedupeBucket).isEqualTo("RUN|${start / 300_000}")
        assertThat(session.userEditedFields).isEmpty()
        assertThat(session.trimp).isNull()
        assertThat(session.loadMethod).isNull()
    }

    @Test
    fun hc13_daily_summary_copies_every_channel_and_leaves_garmin_only_fields_null() {
        val summary = mapper.toDailySummary(
            HcDailySummary(
                day = 20_500L,
                steps = 8_421,
                totalEnergyKcal = 2_910.0,
                activeEnergyKcal = 820.0,
                distanceMeters = 6_200.0,
                floors = 12.0,
                restingHr = 48,
                avgSpo2Percent = 96.5,
                avgRespiratoryRate = 13.4,
                hrvRmssdMs = 62.0,
                vo2Max = 54.0,
            ),
            updatedAtMillis = 7L,
        )

        assertThat(summary.day).isEqualTo(20_500L)
        assertThat(summary.steps).isEqualTo(8_421)
        assertThat(summary.totalEnergyKcal).isEqualTo(2_910.0)
        assertThat(summary.activeEnergyKcal).isEqualTo(820.0)
        assertThat(summary.restingHr).isEqualTo(48)
        assertThat(summary.floors).isEqualTo(12.0)
        assertThat(summary.hrvRmssdMs).isEqualTo(62.0)
        assertThat(summary.vo2Max).isEqualTo(54.0)
        assertThat(summary.source).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(summary.updatedAtMillis).isEqualTo(7L)
        assertThat(summary.bodyBattery).isNull()
        assertThat(summary.stressAvg).isNull()
        assertThat(summary.trainingReadiness).isNull()
    }

    @Test
    fun hc14_sleep_stages_map_to_the_domain_enum_and_fill_the_minute_columns() {
        val start = at("2026-03-01T23:00:00")
        val sleep = HcSleep(
            externalId = "sl-1",
            packageName = "com.garmin",
            startMillis = start,
            endMillis = start + 8 * 3_600_000L,
            stages = listOf(
                HcSleepStage(start, start + 3_600_000L, SleepSessionRecord.STAGE_TYPE_LIGHT),
                HcSleepStage(
                    start + 3_600_000L,
                    start + 2 * 3_600_000L,
                    SleepSessionRecord.STAGE_TYPE_DEEP,
                ),
                HcSleepStage(
                    start + 2 * 3_600_000L,
                    start + 3 * 3_600_000L,
                    SleepSessionRecord.STAGE_TYPE_REM,
                ),
                HcSleepStage(
                    start + 3 * 3_600_000L,
                    start + 3 * 3_600_000L + 1_800_000L,
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                ),
            ),
        )

        val record = mapper.toSleepRecords(listOf(sleep), tz).single()

        assertThat(record.night).isEqualTo(java.time.LocalDate.of(2026, 3, 2).toEpochDay())
        assertThat(record.lightMin).isEqualTo(60)
        assertThat(record.deepMin).isEqualTo(60)
        assertThat(record.remMin).isEqualTo(60)
        assertThat(record.awakeMin).isEqualTo(30)
        assertThat(record.totalSleepMin).isEqualTo(180)
        assertThat(record.stages?.map { it.stage })
            .containsExactly(
                SleepStage.LIGHT,
                SleepStage.DEEP,
                SleepStage.REM,
                SleepStage.AWAKE,
            ).inOrder()
        assertThat(record.externalId).isEqualTo("sl-1")
        assertThat(record.source).isEqualTo(ActivitySource.HEALTH_CONNECT)
    }

    @Test
    fun hc15_two_sessions_on_the_same_night_are_merged_into_one_record() {
        val first = HcSleep(
            externalId = "sl-a",
            packageName = null,
            startMillis = at("2026-03-01T22:30:00"),
            endMillis = at("2026-03-02T02:00:00"),
            stages = listOf(
                HcSleepStage(
                    at("2026-03-01T22:30:00"),
                    at("2026-03-02T02:00:00"),
                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                ),
            ),
        )
        val second = HcSleep(
            externalId = "sl-b",
            packageName = null,
            startMillis = at("2026-03-02T02:30:00"),
            endMillis = at("2026-03-02T06:30:00"),
            stages = listOf(
                HcSleepStage(
                    at("2026-03-02T02:30:00"),
                    at("2026-03-02T06:30:00"),
                    SleepSessionRecord.STAGE_TYPE_DEEP,
                ),
            ),
        )

        val records = mapper.toSleepRecords(listOf(second, first), tz)

        assertThat(records).hasSize(1)
        val merged = records.single()
        assertThat(merged.night).isEqualTo(java.time.LocalDate.of(2026, 3, 2).toEpochDay())
        assertThat(merged.startAtMillis).isEqualTo(at("2026-03-01T22:30:00"))
        assertThat(merged.endAtMillis).isEqualTo(at("2026-03-02T06:30:00"))
        assertThat(merged.lightMin).isEqualTo(210)
        assertThat(merged.deepMin).isEqualTo(240)
        assertThat(merged.totalSleepMin).isEqualTo(450)
        assertThat(merged.stages).hasSize(2)
        // Earliest session wins the id, so a re-sync produces the same (source, externalId) row.
        assertThat(merged.externalId).isEqualTo("sl-a")
    }

    @Test
    fun hc16_unknown_sleep_stage_degrades_to_unknown_and_does_not_count_as_sleep() {
        val start = at("2026-03-01T23:00:00")
        val sleep = HcSleep(
            externalId = "sl-x",
            packageName = null,
            startMillis = start,
            endMillis = start + 2 * 3_600_000L,
            stages = listOf(
                HcSleepStage(start, start + 3_600_000L, 987),
                HcSleepStage(
                    start + 3_600_000L,
                    start + 2 * 3_600_000L,
                    SleepSessionRecord.STAGE_TYPE_SLEEPING,
                ),
            ),
        )

        val record = mapper.toSleepRecords(listOf(sleep), tz).single()

        assertThat(record.stages?.first()?.stage).isEqualTo(SleepStage.UNKNOWN)
        assertThat(record.totalSleepMin).isEqualTo(60)
        assertThat(SleepStageMap.toSleepStage(987)).isEqualTo(SleepStage.UNKNOWN)
    }

    @Test
    fun hc17_sleep_without_stages_uses_the_session_length_and_null_stage_columns() {
        val sleep = HcSleep(
            externalId = "sl-plain",
            packageName = null,
            startMillis = at("2026-03-01T23:00:00"),
            endMillis = at("2026-03-02T06:00:00"),
        )

        val record = mapper.toSleepRecords(listOf(sleep), tz).single()

        assertThat(record.totalSleepMin).isEqualTo(7 * 60)
        assertThat(record.stages).isNull()
        assertThat(record.lightMin).isNull()
        assertThat(record.deepMin).isNull()
        assertThat(record.remMin).isNull()
        assertThat(record.awakeMin).isNull()
    }

    @Test
    fun hc18_weight_and_body_fat_at_the_same_instant_become_one_measurement() {
        val atMillis = at("2026-03-01T07:15:00")
        val bodies = listOf(
            HcBody("bf-1", "com.garmin", atMillis, bodyFatPercent = 14.2),
            HcBody("w-1", "com.garmin", atMillis, weightKg = 78.4),
            HcBody("w-2", "com.garmin", at("2026-03-02T07:15:00"), weightKg = 78.0),
        )

        val measurements = mapper.toBodyMeasurements(bodies, tz)

        assertThat(measurements).hasSize(2)
        val merged = measurements.first()
        assertThat(merged.measuredAtMillis).isEqualTo(atMillis)
        assertThat(merged.weightKg).isEqualTo(78.4)
        assertThat(merged.bodyFatPercent).isEqualTo(14.2)
        assertThat(merged.externalId).isEqualTo("w-1")
        assertThat(merged.day).isEqualTo(java.time.LocalDate.of(2026, 3, 1).toEpochDay())
        assertThat(merged.source).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(measurements[1].weightKg).isEqualTo(78.0)
        assertThat(measurements[1].bodyFatPercent).isNull()
    }

    @Test
    fun hc19_rowing_and_rowing_machine_map_to_rowing() {
        val onWater = mapper.toSession(
            exercise(type = ExerciseSessionRecord.EXERCISE_TYPE_ROWING),
            tz,
            nowMillis = 1_000L,
        )
        val ergo = mapper.toSession(
            exercise(type = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE),
            tz,
            nowMillis = 1_000L,
        )

        assertThat(onWater.sportType).isEqualTo(SportType.ROWING)
        assertThat(ergo.sportType).isEqualTo(SportType.ROWING)
        assertThat(onWater.sportGroup).isEqualTo(SportGroup.OTHER)
    }

    @Test
    fun hc20_yoga_pilates_and_stretching_map_to_mobility() {
        val types = listOf(
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
        )

        val mapped = types.map { ExerciseTypeMap.toSportType(it) }

        assertThat(mapped).containsExactly(SportType.MOBILITY, SportType.MOBILITY, SportType.MOBILITY)
    }
}

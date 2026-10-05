package com.myhealth.data.applehealth

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.healthconnect.ExerciseTypeMap
import com.myhealth.data.healthconnect.HcExerciseType
import com.myhealth.data.healthconnect.HcRecordKind
import com.myhealth.data.healthconnect.HcSleepStageType
import com.myhealth.domain.model.SportType
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test

/** P22.1: the platform-free half of the Apple Health reader. */
class AppleHealthMappingTest {

    private val berlin = TimeZone.of("Europe/Berlin")

    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(berlin).toEpochMilliseconds()

    private fun day(text: String): Long = kotlinx.datetime.LocalDate.parse(text).toEpochDays().toLong()

    @Test
    fun ah01_workout_types_reach_the_same_sports_as_on_android() {
        fun sport(type: Long, indoor: Boolean? = null, swim: Long? = null) =
            ExerciseTypeMap.toSportType(AppleWorkoutType.toHcExerciseType(type, indoor, swim))

        assertThat(sport(AppleWorkoutType.RUNNING)).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(sport(AppleWorkoutType.RUNNING, indoor = true)).isEqualTo(SportType.RUN_TREADMILL)
        assertThat(sport(AppleWorkoutType.CYCLING)).isEqualTo(SportType.CYCLING)
        assertThat(sport(AppleWorkoutType.CYCLING, indoor = true)).isEqualTo(SportType.CYCLING_INDOOR)
        assertThat(sport(AppleWorkoutType.SOCCER)).isEqualTo(SportType.SOCCER_TRAINING)
        assertThat(sport(AppleWorkoutType.TRADITIONAL_STRENGTH_TRAINING)).isEqualTo(SportType.STRENGTH)
        assertThat(sport(AppleWorkoutType.FUNCTIONAL_STRENGTH_TRAINING)).isEqualTo(SportType.STRENGTH)
        assertThat(sport(AppleWorkoutType.WALKING)).isEqualTo(SportType.WALK)
        assertThat(sport(AppleWorkoutType.HIKING)).isEqualTo(SportType.HIKE)
        assertThat(sport(AppleWorkoutType.SWIMMING)).isEqualTo(SportType.SWIM)
        assertThat(AppleWorkoutType.toHcExerciseType(AppleWorkoutType.SWIMMING, null, AppleWorkoutType.SWIM_LOCATION_OPEN_WATER))
            .isEqualTo(HcExerciseType.SWIMMING_OPEN_WATER)
        assertThat(sport(AppleWorkoutType.ROWING, indoor = true)).isEqualTo(SportType.ROWING)
        assertThat(sport(AppleWorkoutType.YOGA)).isEqualTo(SportType.MOBILITY)
        assertThat(sport(AppleWorkoutType.FLEXIBILITY)).isEqualTo(SportType.MOBILITY)
        assertThat(sport(AppleWorkoutType.HIGH_INTENSITY_INTERVAL_TRAINING)).isEqualTo(SportType.HIIT)
        assertThat(sport(AppleWorkoutType.ELLIPTICAL)).isEqualTo(SportType.OTHER)
        assertThat(sport(AppleWorkoutType.OTHER)).isEqualTo(SportType.OTHER)
    }

    @Test
    fun ah02_stage_samples_of_one_source_become_one_night_and_in_bed_is_dropped() {
        val src = "com.garmin.connect.mobile"
        val samples = listOf(
            AppleSleepSample("a", src, at("2026-03-01T22:30"), at("2026-03-02T06:45"), AppleSleepValue.IN_BED),
            AppleSleepSample("b", src, at("2026-03-01T23:00"), at("2026-03-02T01:00"), AppleSleepValue.ASLEEP_CORE),
            AppleSleepSample("c", src, at("2026-03-02T01:00"), at("2026-03-02T02:00"), AppleSleepValue.ASLEEP_DEEP),
            AppleSleepSample("d", src, at("2026-03-02T02:00"), at("2026-03-02T02:10"), AppleSleepValue.AWAKE),
            // a 2 h gap still belongs to the same night
            AppleSleepSample("e", src, at("2026-03-02T04:10"), at("2026-03-02T06:30"), AppleSleepValue.ASLEEP_REM),
        )
        val nights = AppleSleepAssembler.assemble(samples, berlin)
        assertThat(nights).hasSize(1)
        val night = nights.single()
        assertThat(night.externalId).isEqualTo("applehealth-sleep:${day("2026-03-02")}")
        assertThat(night.packageName).isEqualTo(src)
        assertThat(night.startMillis).isEqualTo(at("2026-03-01T23:00"))
        assertThat(night.endMillis).isEqualTo(at("2026-03-02T06:30"))
        assertThat(night.stages.map { it.stage }).containsExactly(
            HcSleepStageType.LIGHT, HcSleepStageType.DEEP, HcSleepStageType.AWAKE, HcSleepStageType.REM,
        ).inOrder()
    }

    @Test
    fun ah03_the_source_with_stages_wins_a_night_and_in_bed_only_keeps_its_envelope() {
        val phone = "com.apple.health"
        val watch = "com.garmin.connect.mobile"
        val samples = listOf(
            AppleSleepSample("p", phone, at("2026-03-01T22:00"), at("2026-03-02T07:00"), AppleSleepValue.IN_BED),
            AppleSleepSample("w", watch, at("2026-03-01T23:00"), at("2026-03-02T06:00"), AppleSleepValue.ASLEEP_CORE),
            // next night: only the phone
            AppleSleepSample("q", phone, at("2026-03-02T23:00"), at("2026-03-03T06:00"), AppleSleepValue.IN_BED),
        )
        val nights = AppleSleepAssembler.assemble(samples, berlin)
        assertThat(nights.map { it.packageName }).containsExactly(watch, phone).inOrder()
        assertThat(nights[0].stages).hasSize(1)
        assertThat(nights[1].stages).isEmpty()
        assertThat(nights[1].startMillis).isEqualTo(at("2026-03-02T23:00"))
        assertThat(nights[1].externalId).isEqualTo("applehealth-sleep:${day("2026-03-03")}")
    }

    @Test
    fun ah04_daily_totals_add_basal_energy_and_convert_units() {
        val d = day("2026-03-02")
        val summaries = AppleDailyAssembler.assemble(
            mapOf(
                AppleDailyMetric.STEPS to mapOf(d to 9_876.4, d + 1 to 120.0),
                AppleDailyMetric.ACTIVE_KCAL to mapOf(d to 600.0),
                AppleDailyMetric.BASAL_KCAL to mapOf(d to 1_650.0),
                AppleDailyMetric.RESTING_HR to mapOf(d to 51.5),
                AppleDailyMetric.SPO2_FRACTION to mapOf(d to 0.965),
                AppleDailyMetric.VO2_MAX to mapOf(d to 48.0),
            ),
        )
        assertThat(summaries.map { it.day }).containsExactly(d, d + 1).inOrder()
        val first = summaries[0]
        assertThat(first.steps).isEqualTo(9_876)
        assertThat(first.activeEnergyKcal).isEqualTo(600.0)
        assertThat(first.totalEnergyKcal).isEqualTo(2_250.0)
        assertThat(first.restingHr).isEqualTo(52)
        assertThat(first.avgSpo2Percent).isWithin(1e-9).of(96.5)
        assertThat(first.vo2Max).isEqualTo(48.0)
        assertThat(first.hrvRmssdMs).isNull()
        val second = summaries[1]
        assertThat(second.steps).isEqualTo(120)
        assertThat(second.totalEnergyKcal).isNull()
    }

    @Test
    fun ah05_anchor_tokens_round_trip_and_garbage_is_rejected() {
        val token = AppleAnchorToken(HcRecordKind.DAILY, mapOf("HKQuantityTypeIdentifierStepCount" to "YWJj"))
        assertThat(AppleAnchorToken.decode(token.encode())).isEqualTo(token)
        assertThat(AppleAnchorToken.decode("health-connect-token")).isNull()
        assertThat(AppleAnchorToken.decode("hk1:{broken")).isNull()
    }
}

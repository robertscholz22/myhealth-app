package com.myhealth.domain.engine.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.HrZoneScheme
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.Sex
import org.junit.Test
import java.time.LocalDate

/**
 * The five-zone model of PLAN §3.9 (`hz01`…`hz10`). `hrMax = 190`, `hrRest = 50` throughout, so
 * the reserve is 140 unless a case says otherwise.
 */
class HrZoneModelTest {

    private val bounds = HrBounds(hrMax = 190, hrRest = 50)

    private fun profile(
        hrZoneBoundsJson: String? = null,
        lactateThresholdHrManual: Int? = null,
    ) = Profile(
        displayName = "Test",
        sex = Sex.MALE,
        birthDay = LocalDate.of(1996, 1, 1).toEpochDay(),
        heightCm = 180.0,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        hrZoneBoundsJson = hrZoneBoundsJson,
        lactateThresholdHrManual = lactateThresholdHrManual,
    )

    /** The lower bounds of Z2…Z5 — the four numbers the whole model is. */
    private fun boundariesOf(model: HrZoneModel): List<Int> = model.zones.drop(1).map { it.lowBpm }

    @Test
    fun hz01_karvonen_boundaries_190_50() {
        val model = HrZoneModel.resolve(profile(), bounds)

        assertThat(model.scheme).isEqualTo(HrZoneScheme.HRR_KARVONEN)
        assertThat(boundariesOf(model)).containsExactly(134, 148, 162, 176).inOrder()
        assertThat(model.warnings).isEmpty()
    }

    @Test
    fun hz02_zone_of_150_is_z3() {
        val model = HrZoneModel.resolve(profile(), bounds)

        // hrr = (150 - 50) / 140 = 0.7143, i.e. the 70-80 % band.
        assertThat(model.zoneOf(150)).isEqualTo(3)
    }

    @Test
    fun hz03_boundary_bpm_belongs_to_the_upper_zone() {
        val model = HrZoneModel.resolve(profile(), bounds)

        assertThat(model.zoneOf(148)).isEqualTo(3)
        assertThat(model.zoneOf(147)).isEqualTo(2)
        assertThat(model.zoneOf(176)).isEqualTo(5)
        assertThat(model.rangeOf(3)).isEqualTo(148..161)
    }

    @Test
    fun hz04_manual_bounds_win() {
        val model = HrZoneModel.resolve(profile(hrZoneBoundsJson = "[130,145,160,172]"), bounds)

        assertThat(model.scheme).isEqualTo(HrZoneScheme.MANUAL)
        assertThat(boundariesOf(model)).containsExactly(130, 145, 160, 172).inOrder()
        assertThat(model.zoneOf(147)).isEqualTo(3)
        assertThat(model.warnings).isEmpty()
    }

    @Test
    fun hz05_lthr_friel_170() {
        val model = HrZoneModel.resolve(profile(lactateThresholdHrManual = 170), bounds)

        // 170 x [0.81, 0.90, 0.94, 1.00] = 137.7, 153.0, 159.8, 170.0, rounded half-up.
        assertThat(model.scheme).isEqualTo(HrZoneScheme.LTHR_FRIEL)
        assertThat(boundariesOf(model)).containsExactly(138, 153, 160, 170).inOrder()
    }

    @Test
    fun hz06_default_model_reproduces_time_in_zones() {
        val offsets = intArrayOf(0, 60, 120, 180, 240, 300, 360)
        val hr = listOf(100, 140, 155, 170, 185, 133, 185)
        val streams = ActivityStreams(
            sampleOffsetsSec = offsets,
            hr = hr,
            sampleCount = hr.size,
            medianIntervalSec = 60.0,
        )

        val legacy = timeInZones(offsets, hr, hrRest = 50, hrMax = 190)
        val model = HrZoneModel.resolve(profile(), bounds).minutesPerZone(streams)

        assertThat(model).isEqualTo(legacy)
        assertThat(model).containsExactly(2.0, 1.0, 1.0, 1.0, 1.0).inOrder()
    }

    @Test
    fun hz07_session_targets_table() {
        assertThat(SessionZoneTargets.targetFor(SessionType.RECOVERY_RUN)).isEqualTo(1..1)
        assertThat(SessionZoneTargets.targetFor(SessionType.EASY_RUN)).isEqualTo(2..2)
        assertThat(SessionZoneTargets.targetFor(SessionType.LONG_RUN)).isEqualTo(2..2)
        assertThat(SessionZoneTargets.targetFor(SessionType.TEMPO_RUN)).isEqualTo(3..4)
        assertThat(SessionZoneTargets.targetFor(SessionType.INTERVAL_RUN)).isEqualTo(4..5)
        assertThat(SessionZoneTargets.targetFor(SessionType.CROSS_TRAINING)).isEqualTo(2..2)
        assertThat(SessionZoneTargets.targetFor(SessionType.MOBILITY)).isEqualTo(1..1)
        assertThat(SessionZoneTargets.targetFor(SessionType.ENDURANCE_RIDE)).isEqualTo(2..2)
        assertThat(SessionZoneTargets.targetFor(SessionType.BIKE_INTERVALS)).isEqualTo(4..5)
        assertThat(SessionZoneTargets.targetFor(SessionType.TRAINER_SESSION)).isEqualTo(3..3)
        assertThat(SessionZoneTargets.targetFor(SessionType.RECOVERY_SPIN)).isEqualTo(1..1)
        assertThat(SessionZoneTargets.targetFor(SessionType.STRENGTH_FULL)).isNull()
        assertThat(SessionZoneTargets.targetFor(SessionType.STRENGTH_UPPER)).isNull()
        assertThat(SessionZoneTargets.targetFor(SessionType.STRENGTH_LOWER)).isNull()
        assertThat(SessionZoneTargets.targetFor(SessionType.SOCCER_MATCH)).isNull()
        assertThat(SessionZoneTargets.targetFor(SessionType.SOCCER_TRAINING)).isNull()
        assertThat(SessionZoneTargets.targetFor(SessionType.REST)).isNull()
    }

    @Test
    fun hz08_non_ascending_manual_bounds_are_ignored() {
        val model = HrZoneModel.resolve(profile(hrZoneBoundsJson = "[160,145,150,172]"), bounds)

        assertThat(model.scheme).isEqualTo(HrZoneScheme.HRR_KARVONEN)
        assertThat(boundariesOf(model)).containsExactly(134, 148, 162, 176).inOrder()
        assertThat(model.warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)

        // The same happens for a blob that is not four numbers at all, or lies outside the bounds.
        assertThat(HrZoneModel.resolve(profile(hrZoneBoundsJson = "[130,145]"), bounds).scheme)
            .isEqualTo(HrZoneScheme.HRR_KARVONEN)
        assertThat(HrZoneModel.resolve(profile(hrZoneBoundsJson = "[130,145,160,199]"), bounds).warnings)
            .isNotEmpty()
    }

    @Test
    fun hz09_z5_has_no_upper_bound() {
        val model = HrZoneModel.resolve(profile(), bounds)

        assertThat(model.zones).hasSize(5)
        assertThat(model.zones.last().highBpm).isNull()
        assertThat(model.zones.last().index).isEqualTo(5)
        assertThat(model.zoneOf(240)).isEqualTo(5)
        // Every other zone ends one bpm below the next one's floor.
        assertThat(model.zones.dropLast(1).map { it.highBpm }).containsExactly(133, 147, 161, 175).inOrder()
    }

    @Test
    fun hz10_polarisation_split() {
        val split = PolarisationSplit.of(listOf(200.0, 100.0, 40.0, 20.0, 40.0))

        assertThat(split.totalMinutes).isWithin(1e-9).of(400.0)
        assertThat(split.easyShare).isWithin(1e-9).of(0.75)
        assertThat(split.hardShare).isWithin(1e-9).of(0.15)
        // An athlete who recorded nothing gets zeroes, not a division by zero.
        assertThat(PolarisationSplit.of(listOf(0.0, 0.0, 0.0, 0.0, 0.0)).easyShare).isEqualTo(0.0)
    }
}

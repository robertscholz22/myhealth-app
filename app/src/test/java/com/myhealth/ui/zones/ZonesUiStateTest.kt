package com.myhealth.ui.zones

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.HrZone
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.PolarisationSplit
import com.myhealth.domain.engine.running.PaceConfidence
import com.myhealth.domain.engine.running.PaceZoneBand
import com.myhealth.domain.engine.suggest.IntervalStructures
import com.myhealth.domain.model.HrZoneScheme
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.WorkoutStep
import com.myhealth.domain.model.WorkoutStepKind
import com.myhealth.domain.model.WorkoutStructure
import com.myhealth.domain.model.WorkoutTargetKind
import org.junit.Test

/** The Zones & paces screen's pure display logic (PLAN §4.2 "Zones & paces", P14.6). */
class ZonesUiStateTest {

    private fun band(
        zone: Int,
        low: Int? = null,
        high: Int? = null,
        confidence: PaceConfidence = PaceConfidence.NONE,
        activities: Int = 0,
    ) = PaceZoneBand(
        zone = zone,
        centreSecPerKm = if (low != null && high != null) (low + high) / 2 else null,
        lowSecPerKm = low,
        highSecPerKm = high,
        confidence = confidence,
        inZoneSec = 0.0,
        activities = activities,
    )

    @Test
    fun zui01_zone_row_label() {
        val zone = HrZone(index = 4, lowBpm = 162, highBpm = 175, nameKey = "hr_zone_4_name")
        assertThat(zoneRowLabel(zone, "Threshold")).isEqualTo("Z4 · Threshold · 162–175 bpm")
    }

    @Test
    fun zui02_open_ended_z5_label() {
        val zone = HrZone(index = 5, lowBpm = 176, highBpm = null, nameKey = "hr_zone_5_name")
        assertThat(zoneRowLabel(zone, "VO2max")).isEqualTo("Z5 · VO2max · 176+ bpm")
    }

    @Test
    fun zui03_pace_band_label() {
        val measured = band(zone = 2, low = 249, high = 262)
        assertThat(paceBandLabel(measured)).isEqualTo("4:09–4:22 /km")

        val noBand = band(zone = 1)
        assertThat(paceBandLabel(noBand)).isNull()
    }

    @Test
    fun zui04_confidence_line_per_confidence() {
        assertThat(confidenceMessageKind(PaceConfidence.HIGH)).isEqualTo(ConfidenceMessageKind.MEASURED)
        assertThat(confidenceMessageKind(PaceConfidence.MEDIUM)).isEqualTo(ConfidenceMessageKind.PARTLY_MODELLED)
        assertThat(confidenceMessageKind(PaceConfidence.LOW)).isEqualTo(ConfidenceMessageKind.PARTLY_MODELLED)
        assertThat(confidenceMessageKind(PaceConfidence.MODELLED)).isEqualTo(ConfidenceMessageKind.MODELLED)
        assertThat(confidenceMessageKind(PaceConfidence.NONE)).isEqualTo(ConfidenceMessageKind.NOT_ENOUGH_DATA)
    }

    @Test
    fun zui05_polarisation_percentages_75_10_15() {
        val split = PolarisationSplit(easyShare = 0.75, hardShare = 0.15, totalMinutes = 400.0)
        assertThat(easyPercent(split)).isEqualTo(75)
        assertThat(moderatePercent(split)).isEqualTo(10)
        assertThat(hardPercent(split)).isEqualTo(15)
        assertThat(showsPolarisationHint(split)).isFalse()

        val unpolarised = PolarisationSplit(easyShare = 0.50, hardShare = 0.30, totalMinutes = 200.0)
        assertThat(showsPolarisationHint(unpolarised)).isTrue()

        assertThat(showsPolarisationHint(PolarisationSplit(0.0, 0.0, 0.0))).isFalse()
    }

    @Test
    fun zui06_structure_summary_five_by_1000_at_354() {
        val structure = WorkoutStructure(
            steps = listOf(
                WorkoutStep(kind = WorkoutStepKind.WARMUP, durationSec = 900, target = WorkoutTargetKind.ZONE, zone = 1),
                WorkoutStep(
                    kind = WorkoutStepKind.REPEAT,
                    repeat = 5,
                    children = listOf(
                        WorkoutStep(
                            kind = WorkoutStepKind.WORK,
                            distanceMeters = 1000.0,
                            target = WorkoutTargetKind.PACE,
                            zone = 5,
                            paceLowSecPerKm = 229,
                            paceHighSecPerKm = 239,
                        ),
                        WorkoutStep(kind = WorkoutStepKind.RECOVERY, durationSec = 120, target = WorkoutTargetKind.ZONE, zone = 1),
                    ),
                ),
                WorkoutStep(kind = WorkoutStepKind.COOLDOWN, durationSec = 600, target = WorkoutTargetKind.ZONE, zone = 1),
            ),
        )
        assertThat(IntervalStructures.summary(structure)).isEqualTo("5 × 1000 m @ 3:54")
    }

    @Test
    fun zui07_session_type_table_rows_incl_na_for_strength_and_soccer() {
        val bounds = HrBounds(hrMax = 190, hrRest = 50)
        val model = HrZoneModel(
            scheme = HrZoneScheme.HRR_KARVONEN,
            zones = HrZoneModel.zonesOf(listOf(134, 148, 162, 176), bounds),
            bounds = bounds,
        )
        val bands = listOf(
            band(1, 300, 320, PaceConfidence.MODELLED, 0),
            band(2, 320, 340, PaceConfidence.HIGH, 5),
            band(3, 280, 300, PaceConfidence.MODELLED, 0),
            band(4, 250, 262, PaceConfidence.MODELLED, 0),
            band(5, 229, 239, PaceConfidence.MODELLED, 0),
        )
        val rows = sessionZoneRows(model, bands)

        val easyRun = rows.single { it.sessionType == SessionType.EASY_RUN }
        assertThat(easyRun.zoneLabel).isEqualTo("Z2 · 134–147 bpm")
        assertThat(easyRun.paceLabel).isNotNull()

        val strength = rows.single { it.sessionType == SessionType.STRENGTH_FULL }
        assertThat(strength.zoneLabel).isNull()
        assertThat(strength.paceLabel).isNull()

        val soccer = rows.single { it.sessionType == SessionType.SOCCER_MATCH }
        assertThat(soccer.zoneLabel).isNull()
        assertThat(soccer.paceLabel).isNull()

        val rest = rows.single { it.sessionType == SessionType.REST }
        assertThat(rest.zoneLabel).isNull()
        assertThat(rest.paceLabel).isNull()
    }

    @Test
    fun zui08_empty_state() {
        val empty = ZonesUiState(isLoading = false)
        assertThat(empty.hasAnyData).isFalse()
        assertThat(empty.isEmpty).isTrue()

        val stillLoading = ZonesUiState(isLoading = true)
        assertThat(stillLoading.isEmpty).isFalse()

        val withVdot = ZonesUiState(isLoading = false, vdot = 50.0)
        assertThat(withVdot.hasAnyData).isTrue()
        assertThat(withVdot.isEmpty).isFalse()

        val withMeasuredBand = ZonesUiState(isLoading = false, bands = listOf(band(2, 300, 320, PaceConfidence.HIGH, 5)))
        assertThat(withMeasuredBand.isEmpty).isFalse()

        // A zone model alone (profile only, no runs, no VDOT) is not the empty state (POLISH-16).
        val modelBounds = HrBounds(hrMax = 190, hrRest = 50)
        val withModelOnly = ZonesUiState(
            isLoading = false,
            model = HrZoneModel(
                scheme = HrZoneScheme.HRR_KARVONEN,
                zones = HrZoneModel.zonesOf(listOf(134, 148, 162, 176), modelBounds),
                bounds = modelBounds,
            ),
        )
        assertThat(withModelOnly.hasAnyData).isTrue()
        assertThat(withModelOnly.isEmpty).isFalse()

        val withPolarisation = ZonesUiState(
            isLoading = false,
            polarisation = PolarisationSplit(easyShare = 0.75, hardShare = 0.15, totalMinutes = 200.0),
        )
        assertThat(withPolarisation.isEmpty).isFalse()
    }
}

package com.myhealth.ui.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.load.AcwrZone
import com.myhealth.domain.engine.load.LoadFlags
import com.myhealth.domain.engine.load.RecoveryFlags
import com.myhealth.domain.model.RecoveryBand
import org.junit.Test

/** Pure UI mappings of `ui/load` (PLAN P5.6): flag → explanation text and the ACWR zone bucket. */
class LoadUiStateTest {

    @Test
    fun flag_explanation_maps_every_known_flag_and_falls_back_for_unknown() {
        assertThat(flagExplanation(LoadFlags.RAMP_HIGH)).contains("15%")
        assertThat(flagExplanation(LoadFlags.HIGH_MONOTONY)).contains("repetitive")
        assertThat(flagExplanation(LoadFlags.HIGH_STRAIN)).contains("strain")
        assertThat(flagExplanation(LoadFlags.NO_REST_DAY_7D)).contains("rest day")
        assertThat(flagExplanation(LoadFlags.INSUFFICIENT_HISTORY)).contains("history")
        assertThat(flagExplanation(RecoveryFlags.SLEEP_DEBT)).contains("Sleep")
        assertThat(flagExplanation("SOME_FUTURE_FLAG")).isEqualTo("SOME_FUTURE_FLAG")
    }

    @Test
    fun acwr_zone_bucket_matches_the_engine_boundaries() {
        assertThat(acwrZoneOf(null)).isNull()
        assertThat(acwrZoneOf(0.79)).isEqualTo(AcwrZone.DETRAINING)
        assertThat(acwrZoneOf(0.80)).isEqualTo(AcwrZone.OPTIMAL)
        assertThat(acwrZoneOf(1.30)).isEqualTo(AcwrZone.OPTIMAL)
        assertThat(acwrZoneOf(1.31)).isEqualTo(AcwrZone.CAUTION)
        assertThat(acwrZoneOf(1.50)).isEqualTo(AcwrZone.CAUTION)
        assertThat(acwrZoneOf(1.51)).isEqualTo(AcwrZone.HIGH_RISK)
    }

    @Test
    fun recovery_band_label_covers_every_band_and_the_null_case() {
        assertThat(recoveryBandLabel(null)).isEqualTo("Not enough data")
        assertThat(recoveryBandLabel(RecoveryBand.FRESH)).isEqualTo("Fresh")
        assertThat(recoveryBandLabel(RecoveryBand.GOOD)).isEqualTo("Good")
        assertThat(recoveryBandLabel(RecoveryBand.MODERATE)).isEqualTo("Moderate")
        assertThat(recoveryBandLabel(RecoveryBand.FATIGUED)).isEqualTo("Fatigued")
        assertThat(recoveryBandLabel(RecoveryBand.STRAINED)).isEqualTo("Strained")
    }
}

package com.myhealth.ui.running

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.running.CanonicalDistances
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Pure time/pace/distance formatting of `ui/running` (PLAN P5.7). `distanceLabel`'s fallback
 * branch formats through `fmtKm` (POLISH-12), which follows `Locale.getDefault()`; pinned to
 * `Locale.US` here so the verbatim assertion below is locale-independent. */
class RunningPrsUiStateTest {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun format_race_time_switches_to_hh_mm_ss_past_an_hour() {
        assertThat(formatRaceTime(75)).isEqualTo("1:15")
        assertThat(formatRaceTime(1200)).isEqualTo("20:00")
        assertThat(formatRaceTime(3661)).isEqualTo("1:01:01")
        assertThat(formatRaceTime(7_200)).isEqualTo("2:00:00")
    }

    @Test
    fun format_pace_pads_seconds_to_two_digits() {
        assertThat(formatPaceSecPerKm(245)).isEqualTo("4:05 /km")
        assertThat(formatPaceSecPerKm(300)).isEqualTo("5:00 /km")
        assertThat(formatPaceSecPerKm(59)).isEqualTo("0:59 /km")
    }

    @Test
    fun distance_label_names_every_canonical_distance() {
        assertThat(distanceLabel(CanonicalDistances.ONE_KM)).isEqualTo("1 km")
        assertThat(distanceLabel(CanonicalDistances.MILE)).isEqualTo("1 mile")
        assertThat(distanceLabel(CanonicalDistances.FIVE_KM)).isEqualTo("5 km")
        assertThat(distanceLabel(CanonicalDistances.TEN_KM)).isEqualTo("10 km")
        assertThat(distanceLabel(CanonicalDistances.HALF_MARATHON)).isEqualTo("Half marathon")
        assertThat(distanceLabel(CanonicalDistances.MARATHON)).isEqualTo("Marathon")
        assertThat(distanceLabel(7_000.0)).isEqualTo("7.0 km")
    }
}

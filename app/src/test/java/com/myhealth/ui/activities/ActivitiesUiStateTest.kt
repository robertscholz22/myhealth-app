package com.myhealth.ui.activities

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import org.junit.Test

/** Pure helpers of `ActivitiesUiState.kt` (PLAN P2.9): month grouping + pace formatting. */
class ActivitiesUiStateTest {

    @Test
    fun act01_groupByMonth_groupsConsecutiveActivitiesByCalendarMonth() {
        // Reverse-chronological input (as the ViewModel supplies it): two September items, one August.
        val activities = listOf(
            fixture(id = 1, day = 19979), // 2024-09-11
            fixture(id = 2, day = 19970), // 2024-09-02
            fixture(id = 3, day = 19960), // 2024-08-23
        )

        val groups = activities.groupByMonth()

        assertThat(groups).hasSize(2)
        assertThat(groups[0].label).isEqualTo("September 2024")
        assertThat(groups[0].items.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(groups[1].label).isEqualTo("August 2024")
        assertThat(groups[1].items.map { it.id }).containsExactly(3L)
    }

    @Test
    fun act02_formatPaceMinPerKm_computesMinutesSecondsPerKilometre() {
        // 1000 m in 300 s -> speed 3.3333 m/s -> pace 300 s/km -> "5:00 /km".
        assertThat(formatPaceMinPerKm(1000.0 / 300.0)).isEqualTo("5:00 /km")
        // 1000 m in 330 s -> pace 330 s/km -> "5:30 /km".
        assertThat(formatPaceMinPerKm(1000.0 / 330.0)).isEqualTo("5:30 /km")
    }

    @Test
    fun act03_formatPaceMinPerKm_nullWhenSpeedMissingOrNonPositive() {
        assertThat(formatPaceMinPerKm(null)).isNull()
        assertThat(formatPaceMinPerKm(0.0)).isNull()
        assertThat(formatPaceMinPerKm(-1.0)).isNull()
    }

    private fun fixture(id: Long, day: Long): ActivitySummary = ActivitySummary(
        id = id,
        startAtMillis = day * 86_400_000L,
        endAtMillis = day * 86_400_000L + 3_600_000L,
        day = day,
        sportType = SportType.RUN_OUTDOOR,
        sportGroup = SportGroup.RUN,
        title = null,
        durationSec = 1800,
        elapsedSec = 1800,
        distanceMeters = 5000.0,
        activeEnergyKcal = null,
        totalEnergyKcal = null,
        avgHr = null,
        maxHr = null,
        avgSpeedMps = null,
        maxSpeedMps = null,
        avgCadenceSpm = null,
        elevationGainM = null,
        trimp = null,
        loadMethod = LoadMethod.DURATION_ONLY,
        rpe = null,
        note = null,
        primarySource = ActivitySource.MANUAL,
        mergedSources = listOf(ActivitySource.MANUAL),
        hasStreams = false,
    )
}

package com.myhealth.ui.sleep

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SleepRecord
import kotlinx.datetime.TimeZone
import org.junit.Test

class SleepUiStateTest {

    private val today = 20_500L

    private fun night(night: Long, minutes: Int, startHourUtc: Int = 22, endHourUtc: Int = 6) = SleepRecord(
        id = night, startAtMillis = (night - 1) * DAY + startHourUtc * HOUR + 12 * MIN,
        endAtMillis = night * DAY + endHourUtc * HOUR + 48 * MIN, night = night, totalSleepMin = minutes,
        lightMin = null, deepMin = null, remMin = null, awakeMin = null, stages = null,
        source = ActivitySource.HEALTH_CONNECT, externalId = null, sleepScore = null,
    )

    @Test
    fun nights_are_listed_newest_first_and_the_newest_is_the_latest() {
        val state = SleepUiState(isLoading = false, today = today, nights = listOf(night(today - 2, 400), night(today, 450), night(today - 1, 420)))
        assertThat(state.nightsNewestFirst.map { it.night }).containsExactly(today, today - 1, today - 2).inOrder()
        assertThat(state.latest?.night).isEqualTo(today)
    }

    @Test
    fun only_this_morning_or_yesterday_counts_as_last_night() {
        assertThat(isLastNight(today, today)).isTrue()
        assertThat(isLastNight(today - 1, today)).isTrue()
        assertThat(isLastNight(today - 2, today)).isFalse()
    }

    @Test
    fun the_average_counts_only_recorded_nights() {
        assertThat(averageSleepHours(listOf(night(today, 420), night(today - 1, 480)))).isWithin(1e-9).of(7.5)
        assertThat(averageSleepHours(emptyList())).isNull()
    }

    @Test
    fun bedtime_label_shows_start_and_end_in_the_zone() {
        assertThat(bedtimeLabel(night(today, 450), TimeZone.UTC)).isEqualTo("22:12 – 06:48")
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}

package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The pure day-cell derivation of P3.4: which marker dots a day shows, and its kcal-delta bar. */
class DayCellMarkersTest {

    @Test
    fun markers01_a_day_with_all_four_kinds_shows_four_dots_in_order() {
        val day = CalendarUiFixtures.day(
            events = listOf(CalendarUiFixtures.occurrence()),
            planned = listOf(CalendarUiFixtures.planned()),
            activities = listOf(CalendarUiFixtures.activity()),
            meals = listOf(CalendarUiFixtures.meal()),
        )

        assertThat(dayMarkers(day)).containsExactly(
            DayMarker.EVENT,
            DayMarker.PLANNED,
            DayMarker.ACTIVITY,
            DayMarker.MEAL,
        ).inOrder()
    }

    @Test
    fun markers02_an_empty_or_absent_day_has_no_markers_and_no_kcal_bar() {
        assertThat(dayMarkers(null)).isEmpty()
        assertThat(kcalDeltaBar(null)).isNull()

        val empty = CalendarUiFixtures.day()
        assertThat(dayMarkers(empty)).isEmpty()
        assertThat(kcalDeltaBar(empty)).isNull()

        // A target but nothing logged is still "not both known" — no bar.
        val targetOnly = CalendarUiFixtures.day(target = CalendarUiFixtures.target(kcal = 2000))
        assertThat(kcalDeltaBar(targetOnly)).isNull()
        // …and intake without a target snapshot likewise.
        val intakeOnly = CalendarUiFixtures.day(
            meals = listOf(CalendarUiFixtures.meal()),
            intake = CalendarUiFixtures.intake(kcal = 1800.0),
        )
        assertThat(kcalDeltaBar(intakeOnly)).isNull()
        assertThat(dayMarkers(intakeOnly)).containsExactly(DayMarker.MEAL)
    }

    @Test
    fun markers03_kcal_bar_is_green_under_target_amber_just_over_and_red_past_300() {
        val target = CalendarUiFixtures.target(kcal = 2000)

        val under = kcalDeltaBar(
            CalendarUiFixtures.day(target = target, intake = CalendarUiFixtures.intake(1500.0)),
        )
        assertThat(under!!.level).isEqualTo(KcalDeltaLevel.UNDER)
        assertThat(under.deltaKcal).isEqualTo(-500)
        assertThat(under.fraction).isWithin(1e-4f).of(0.75f)

        // Exactly on target is still green (delta = 0), and the bar is full.
        val onTarget = kcalDeltaBar(
            CalendarUiFixtures.day(target = target, intake = CalendarUiFixtures.intake(2000.0)),
        )
        assertThat(onTarget!!.level).isEqualTo(KcalDeltaLevel.UNDER)
        assertThat(onTarget.fraction).isWithin(1e-4f).of(1.0f)

        // +300 kcal is the last amber value; +301 is red.
        val amber = kcalDeltaBar(
            CalendarUiFixtures.day(target = target, intake = CalendarUiFixtures.intake(2300.0)),
        )
        assertThat(amber!!.level).isEqualTo(KcalDeltaLevel.NEAR)
        assertThat(amber.deltaKcal).isEqualTo(300)
        assertThat(amber.fraction).isWithin(1e-4f).of(1.0f)

        val red = kcalDeltaBar(
            CalendarUiFixtures.day(target = target, intake = CalendarUiFixtures.intake(2301.0)),
        )
        assertThat(red!!.level).isEqualTo(KcalDeltaLevel.OVER)
        assertThat(red.deltaKcal).isEqualTo(301)
    }
}

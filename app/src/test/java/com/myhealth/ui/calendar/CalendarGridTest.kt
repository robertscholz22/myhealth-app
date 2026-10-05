package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.util.minusMonths
import com.myhealth.domain.util.plusDays
import com.myhealth.domain.util.plusMonths
import kotlinx.datetime.number
import org.junit.Test
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * The pure month-grid geometry of P3.4: always 42 cells, Monday first (ISO weeks, §1.6), and the
 * whole anchored month inside them.
 */
class CalendarGridTest {

    @Test
    fun grid01_always_42_cells_starting_on_a_monday() {
        val anchors = listOf(
            LocalDate(2026, 9, 14),
            LocalDate(2026, 2, 10),
            LocalDate(2028, 2, 1),
            LocalDate(2026, 6, 30),
            LocalDate(2027, 12, 31),
        )
        anchors.forEach { anchor ->
            val grid = monthGridDays(anchor)
            assertThat(grid).hasSize(42)
            assertThat(grid.first().dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
            assertThat(grid.last()).isEqualTo(grid.first().plusDays(41))
            assertThat(grid.toSet()).hasSize(42)
        }
    }

    @Test
    fun grid02_contains_the_first_and_the_last_day_of_the_anchored_month() {
        val anchor = LocalDate(2026, 9, 14)
        val grid = monthGridDays(anchor)

        assertThat(grid).contains(LocalDate(2026, 9, 1))
        assertThat(grid).contains(LocalDate(2026, 9, 30))
        // September 2026 starts on a Tuesday: exactly one leading day from August.
        assertThat(grid.first()).isEqualTo(LocalDate(2026, 8, 31))
        assertThat(grid.count { it.month.number == 9 }).isEqualTo(30)
    }

    @Test
    fun grid03_february_2028_leap_year_holds_all_29_days() {
        val grid = monthGridDays(LocalDate(2028, 2, 17))

        assertThat(grid).hasSize(42)
        assertThat(grid).contains(LocalDate(2028, 2, 29))
        assertThat(grid.count { it.month.number == 2 && it.year == 2028 }).isEqualTo(29)
        assertThat(grid.first()).isEqualTo(LocalDate(2028, 1, 31))
    }

    @Test
    fun grid04_month_starting_on_monday_has_no_leading_days() {
        // 1 June 2026 is a Monday.
        val grid = monthGridDays(LocalDate(2026, 6, 5))

        assertThat(grid.first()).isEqualTo(LocalDate(2026, 6, 1))
        assertThat(grid).contains(LocalDate(2026, 6, 30))
        assertThat(grid.last()).isEqualTo(LocalDate(2026, 7, 12))
    }

    @Test
    fun grid05_month_starting_on_sunday_still_fits_in_six_rows() {
        // 1 November 2026 is a Sunday — the worst case: six leading days from October.
        val grid = monthGridDays(LocalDate(2026, 11, 20))

        assertThat(grid.first()).isEqualTo(LocalDate(2026, 10, 26))
        assertThat(grid).contains(LocalDate(2026, 11, 1))
        assertThat(grid).contains(LocalDate(2026, 11, 30))
        assertThat(grid.last()).isEqualTo(LocalDate(2026, 12, 6))
    }

    @Test
    fun grid06_week_days_are_the_monday_first_iso_week() {
        val week = weekDays(LocalDate(2026, 9, 17))

        assertThat(week).hasSize(7)
        assertThat(week.first()).isEqualTo(LocalDate(2026, 9, 14))
        assertThat(week.last()).isEqualTo(LocalDate(2026, 9, 20))
    }

    @Test
    fun grid07_visible_range_covers_the_page_plus_one_page_of_slack() {
        val anchor = LocalDate(2026, 9, 14)
        val month = visibleRange(anchor.toEpochDays(), CalendarMode.MONTH)
        val week = visibleRange(anchor.toEpochDays(), CalendarMode.WEEK)

        assertThat(month.first).isEqualTo(monthGridDays(anchor.minusMonths(1)).first().toEpochDays())
        assertThat(month.last).isEqualTo(monthGridDays(anchor.plusMonths(1)).last().toEpochDays())
        assertThat(month.last - month.first + 1).isAtLeast(42L)
        assertThat(week.first).isEqualTo(LocalDate(2026, 9, 7).toEpochDays())
        assertThat(week.last).isEqualTo(LocalDate(2026, 9, 27).toEpochDays())
    }

    @Test
    fun grid08_formats_titles_and_times() {
        assertThat(monthTitle(LocalDate(2026, 9, 14))).isEqualTo("September 2026")
        assertThat(weekTitle(LocalDate(2026, 9, 17))).isEqualTo("W38 · 14 Sep – 20 Sep")
        assertThat(fullDateTitle(LocalDate(2026, 9, 14))).isEqualTo("Monday, 14 Sep 2026")
        assertThat(formatMinuteOfDay(null)).isNull()
        assertThat(formatMinuteOfDay(7 * 60 + 5)).isEqualTo("07:05")
        assertThat(formatMinuteOfDay(19 * 60 + 30)).isEqualTo("19:30")
    }
}

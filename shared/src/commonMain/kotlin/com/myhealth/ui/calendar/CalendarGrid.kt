package com.myhealth.ui.calendar

import com.myhealth.domain.util.firstOfMonth
import com.myhealth.domain.util.isoWeekOf
import com.myhealth.domain.util.isoWeekStart
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.minusMonths
import com.myhealth.domain.util.minusWeeks
import com.myhealth.domain.util.plusDays
import com.myhealth.domain.util.plusMonths
import com.myhealth.domain.util.plusWeeks
import com.myhealth.ui.common.usText
import com.myhealth.ui.common.zeroPad
import kotlinx.datetime.LocalDate

/**
 * Pure grid geometry for the calendar screen (PLAN §4.2 Calendar, P3.4). No Compose, no Android —
 * unit-tested in `CalendarGridTest`.
 *
 * The month grid is always [MONTH_GRID_CELLS] cells (6 ISO weeks × 7 days, Monday first, §1.6) so
 * paging never changes the grid's height and the layout never reflows between months.
 */

/** 6 rows × 7 columns. Enough for every month: at most 6 leading blanks + 31 days = 37 ≤ 42. */
const val MONTH_GRID_CELLS: Int = 42

/** Monday-first weekday initials for the grid header. */
val WEEKDAY_INITIALS: List<String> = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * The 42 days rendered for the month containing [anchor]: starts on the Monday of the ISO week
 * holding the 1st, runs 6 weeks, and therefore always contains every day of that month. Days
 * outside the month are the neighbouring months' days (rendered dimmed).
 */
fun monthGridDays(anchor: LocalDate): List<LocalDate> {
    val gridStart = anchor.firstOfMonth().isoWeekStart()
    return List(MONTH_GRID_CELLS) { index -> gridStart.plusDays(index.toLong()) }
}

/** The 7 days of the ISO week containing [anchor], Monday first. */
fun weekDays(anchor: LocalDate): List<LocalDate> {
    val weekStart = anchor.isoWeekStart()
    return List(7) { index -> weekStart.plusDays(index.toLong()) }
}

/** The epoch-day range the screen must observe for [anchorDay], one page of slack on each side. */
fun visibleRange(anchorDay: Long, mode: CalendarMode): LongRange {
    val anchor = anchorDay.epochDayDate()
    return when (mode) {
        CalendarMode.MONTH ->
            monthGridDays(anchor.minusMonths(1)).first().toEpochDays()..
                monthGridDays(anchor.plusMonths(1)).last().toEpochDays()
        CalendarMode.WEEK ->
            weekDays(anchor).first().minusWeeks(1).toEpochDays()..
                weekDays(anchor).last().plusWeeks(1).toEpochDays()
    }
}

private const val MONTH_TITLE = "MMMM yyyy"
private const val DAY_MONTH = "d MMM"
private const val FULL_DATE = "EEEE, d MMM yyyy"

/** "September 2026". */
fun monthTitle(anchor: LocalDate): String = anchor.usText(MONTH_TITLE)

/** "W38 · 14 Sep – 20 Sep". */
fun weekTitle(anchor: LocalDate): String {
    val days = weekDays(anchor)
    return "W${isoWeekOf(anchor).week} · ${days.first().usText(DAY_MONTH)} – ${days.last().usText(DAY_MONTH)}"
}

/** "Monday, 14 Sep 2026" — the agenda / day-detail header. */
fun fullDateTitle(date: LocalDate): String = date.usText(FULL_DATE)

/** "07:30" from a minute-of-day, or `null` for an all-day item. */
fun formatMinuteOfDay(minuteOfDay: Int?): String? {
    if (minuteOfDay == null) return null
    val clamped = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    return "${(clamped / 60).zeroPad(2)}:${(clamped % 60).zeroPad(2)}"
}

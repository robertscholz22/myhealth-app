package com.myhealth.ui.calendar

import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.util.epochDayDate
import kotlinx.datetime.LocalDate

/** Month grid or week strip + agenda (PLAN §4.2 Calendar). */
enum class CalendarMode { MONTH, WEEK }

/** Tolerant decode of the persisted [CalendarMode] name (`SavedStateHandle` round-trip). */
fun calendarModeOf(name: String?): CalendarMode =
    CalendarMode.entries.firstOrNull { it.name == name } ?: CalendarMode.MONTH

/**
 * ViewModel state for [CalendarScreen] (PLAN §4.2 Calendar, P3.4).
 *
 * [days] holds the whole observed window (the visible page plus one page of slack on each side),
 * keyed by epoch day; a day missing from the map simply has nothing on it.
 */
data class CalendarUiState(
    val mode: CalendarMode = CalendarMode.MONTH,
    val anchorDay: Long = 0L,
    val selectedDay: Long = 0L,
    val today: Long = 0L,
    val days: Map<Long, CalendarDay> = emptyMap(),
    /** One [CycleDayMarker] per day in [days]'s range, or empty while tracking is off (P11.3). */
    val cycleMarkers: Map<Long, CycleDayMarker> = emptyMap(),
    val isLoading: Boolean = true,
) {
    val anchorDate: LocalDate get() = anchorDay.epochDayDate()

    val selectedDate: LocalDate get() = selectedDay.epochDayDate()

    /** Top-bar title: "September 2026" in month mode, "W38 · 14 Sep – 20 Sep" in week mode. */
    val title: String
        get() = when (mode) {
            CalendarMode.MONTH -> monthTitle(anchorDate)
            CalendarMode.WEEK -> weekTitle(anchorDate)
        }

    fun dayAt(epochDay: Long): CalendarDay? = days[epochDay]

    val selected: CalendarDay? get() = days[selectedDay]
}

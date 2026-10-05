package com.myhealth.ui.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.cycle.CycleEngine
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.util.epochDayDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

private const val KEY_ANCHOR = "calendar.anchorDay"
private const val KEY_MODE = "calendar.mode"
private const val KEY_SELECTED = "calendar.selectedDay"

/**
 * Backs [CalendarScreen] (PLAN §4.2 Calendar, P3.4).
 *
 * [anchorDay], the mode and the selected day live in the [SavedStateHandle], so a process death
 * mid-scroll restores the same page. The visible window is recomputed whenever either changes and
 * re-subscribed with [flatMapLatest], so only the days on screen (plus one page of slack on each
 * side, for a smooth swipe) are ever observed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    calendarRepo: CalendarRepository,
    cycleRepo: CycleRepository,
    private val savedState: SavedStateHandle,
    clock: PlatformClock,
) : ViewModel() {

    private val today: Long = clock.today().toEpochDays()

    private val anchorDay: StateFlow<Long> = savedState.getStateFlow(KEY_ANCHOR, today)
    private val modeName: StateFlow<String> = savedState.getStateFlow(KEY_MODE, CalendarMode.MONTH.name)
    private val selectedDay: StateFlow<Long> = savedState.getStateFlow(KEY_SELECTED, today)

    private val window: Flow<LongRange> = combine(anchorDay, modeName) { anchor, mode ->
        visibleRange(anchor, calendarModeOf(mode))
    }.distinctUntilChanged()

    private val days: Flow<Map<Long, CalendarDay>> = window.flatMapLatest { range ->
        calendarRepo.observeRange(range.first, range.last)
    }

    /** One [CycleDayMarker] per day of [window], computed straight from
     * [com.myhealth.domain.engine.cycle.CycleEngine] (pure) over the logged entries — empty while
     * tracking is off, so a day with no cycle data never even calls into the engine (P11.3). */
    private val cycleMarkers: Flow<Map<Long, CycleDayMarker>> = window.flatMapLatest { range ->
        combine(cycleRepo.isTrackingEnabled(), cycleRepo.observeAll()) { enabled, entries -> enabled to entries }
            .flatMapLatest { (enabled, entries) ->
                if (!enabled) {
                    flowOf(emptyMap())
                } else {
                    flowOf(
                        CycleEngine.statusesFor(range.first, range.last + 1, entries)
                            .mapNotNull { (day, status) -> cycleDayMarker(status)?.let { day to it } }
                            .toMap(),
                    )
                }
            }
    }

    val state: StateFlow<CalendarUiState> =
        combine(anchorDay, modeName, selectedDay, days, cycleMarkers) { anchor, mode, selected, dayMap, markers ->
            CalendarUiState(
                mode = calendarModeOf(mode),
                anchorDay = anchor,
                selectedDay = selected,
                today = today,
                days = dayMap,
                cycleMarkers = markers,
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    private fun initialState(): CalendarUiState = CalendarUiState(
        mode = calendarModeOf(modeName.value),
        anchorDay = anchorDay.value,
        selectedDay = selectedDay.value,
        today = today,
    )

    /** Called when the pager settles on another month/week. */
    fun setAnchor(day: Long) {
        savedState[KEY_ANCHOR] = day
    }

    fun setMode(mode: CalendarMode) {
        savedState[KEY_MODE] = mode.name
        // Keep the selection visible when switching: anchor the new page on the selected day.
        savedState[KEY_ANCHOR] = selectedDay.value
    }

    fun selectDay(day: Long) {
        savedState[KEY_SELECTED] = day
        val anchor = anchorDay.value.epochDayDate()
        val selected = day.epochDayDate()
        if (calendarModeOf(modeName.value) == CalendarMode.WEEK || selected.month != anchor.month) {
            savedState[KEY_ANCHOR] = day
        }
    }

    fun goToToday() {
        savedState[KEY_ANCHOR] = today
        savedState[KEY_SELECTED] = today
    }
}

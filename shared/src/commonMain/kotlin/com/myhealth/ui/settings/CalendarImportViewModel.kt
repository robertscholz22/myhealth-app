package com.myhealth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.di.CalendarGroup
import com.myhealth.di.CalendarImport
import com.myhealth.di.CalendarImportOverview
import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.DeviceCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarImportUiState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val accessDenied: Boolean = false,
    val failed: Boolean = false,
    val overview: CalendarImportOverview = CalendarImportOverview(),
)

/** Backs [CalendarImportScreen]: every change is applied (and imported) at once. */
class CalendarImportViewModel(private val import: CalendarImport) : ViewModel() {

    private val _state = MutableStateFlow(CalendarImportUiState())
    val state: StateFlow<CalendarImportUiState> = _state.asStateFlow()

    /** A rule edit that is still being typed (the duration field reports every keystroke). */
    private var pendingRule: Job? = null

    init {
        refresh()
    }

    fun refresh() = apply { import.refresh() }

    fun onAccessResult(granted: Boolean) {
        _state.update { it.copy(accessDenied = !granted) }
        if (granted) refresh()
    }

    fun setSelected(calendar: DeviceCalendar, selected: Boolean) {
        _state.update { state ->
            state.copy(
                overview = state.overview.copy(
                    calendars = state.overview.calendars.map {
                        if (it.calendar.id == calendar.id) it.copy(selected = selected) else it
                    },
                ),
            )
        }
        apply { import.setSelected(calendar, selected) }
    }

    fun setRule(group: CalendarGroup, rule: CalendarClusterRule) {
        // Shown at once; saved when the value has stood for a moment.
        _state.update { state ->
            state.copy(
                overview = state.overview.copy(
                    groups = state.overview.groups.map {
                        if (it.calendarName == group.calendarName && it.cluster.key == group.cluster.key) it.copy(rule = rule) else it
                    },
                ),
            )
        }
        pendingRule?.cancel()
        pendingRule = viewModelScope.launch {
            delay(RULE_SETTLE_MILLIS)
            run { import.setRule(group.calendarName, group.cluster.key, rule) }
        }
    }

    fun restoreRemoved() = apply { import.restoreRemoved() }

    private fun apply(block: suspend () -> CalendarImportOverview) {
        viewModelScope.launch { run(block) }
    }

    private suspend fun run(block: suspend () -> CalendarImportOverview) {
        _state.update { it.copy(isWorking = true, failed = false) }
        try {
            val overview = block()
            _state.update { it.copy(isLoading = false, isWorking = false, overview = overview) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, isWorking = false, failed = true) }
        }
    }

    private companion object {
        const val RULE_SETTLE_MILLIS = 500L
    }
}

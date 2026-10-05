package com.myhealth.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.epochDayDate
import com.myhealth.resources.*
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * Backs [EventEditScreen] (PLAN §4.2 Event edit, P3.6): create/edit a [com.myhealth.domain.model.CalendarEvent]
 * with the RFC-5545-subset recurrence of §2.2.4, and delete it — "this occurrence only" (an
 * `event_override` `SKIP`) or "whole series" for a recurring event, a direct delete otherwise.
 *
 * [id] `-1` means "new event"; [epochDay] is the occurrence day the screen was opened from (the
 * Calendar FAB's selected day, or the Day detail row's occurrence day) — it seeds a new event's
 * date and, for an existing recurring event, is the day "this occurrence only" applies to.
 */
class EventEditViewModel(
    private val id: Long,
    private val epochDay: Long,
    private val calendarRepo: CalendarRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: PlatformClock,
) : ViewModel() {

    private val _state = MutableStateFlow(EventEditUiState(isLoading = id != -1L, isNew = id == -1L))
    val state: StateFlow<EventEditUiState> = _state.asStateFlow()

    init {
        if (id == -1L) {
            val seedDay = if (epochDay >= 0) epochDay.epochDayDate() else clock.today()
            _state.update {
                it.copy(
                    isLoading = false,
                    draft = newEventDraft(seedDay),
                    occurrenceDay = seedDay.toEpochDays(),
                )
            }
        } else {
            loadEvent()
        }
    }

    private fun loadEvent() {
        viewModelScope.launch {
            val event = calendarRepo.getEvent(id)
            if (event == null) {
                _state.update { it.copy(isLoading = false, loadError = UiMessage.of(Res.string.event_load_not_found)) }
                return@launch
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    draft = eventDraftFrom(event),
                    isRecurring = event.recurrenceRule != null,
                    occurrenceDay = if (epochDay >= 0) epochDay else event.startDay,
                )
            }
        }
    }

    fun updateDraft(transform: (EventDraft) -> EventDraft) {
        _state.update {
            val draft = transform(it.draft)
            it.copy(draft = draft, errors = validate(draft))
        }
    }

    fun save() {
        val current = _state.value
        val errors = validate(current.draft)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            val event = current.draft.toCalendarEvent(clock)
            when (calendarRepo.upsertEvent(event)) {
                is Outcome.Ok -> {
                    // A match/race on this day or the next one changes its DayType (P4.12).
                    syncScheduler.requestTargetRecompute()
                    _state.update { it.copy(isSaving = false, saved = true) }
                }
                is Outcome.Err -> _state.update {
                    it.copy(isSaving = false, saveError = UiMessage.of(Res.string.event_save_failed))
                }
            }
        }
    }

    fun requestDelete() {
        _state.update { it.copy(pendingDelete = true) }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = false) }
    }

    /** Non-recurring delete, or the recurring "whole series" choice: removes the definition row
     * (and its overrides, `CASCADE`, §2.2.4). */
    fun deleteSeries() {
        _state.update { it.copy(pendingDelete = false) }
        viewModelScope.launch {
            when (calendarRepo.deleteEvent(id)) {
                is Outcome.Ok -> {
                    syncScheduler.requestTargetRecompute()
                    _state.update { it.copy(deleted = true) }
                }
                is Outcome.Err -> _state.update { it.copy(saveError = UiMessage.of(Res.string.event_delete_series_failed)) }
            }
        }
    }

    /** The recurring "this occurrence only" choice: a `SKIP` override for the shown occurrence
     * day, leaving the rest of the series untouched. */
    fun deleteOccurrence() {
        val day = _state.value.occurrenceDay
        _state.update { it.copy(pendingDelete = false) }
        viewModelScope.launch {
            val override = EventOverride(
                id = 0L,
                eventId = id,
                occurrenceDay = day,
                action = "SKIP",
                newStartDay = null,
                newStartMinuteOfDay = null,
                newDurationMin = null,
                newTitle = null,
            )
            when (calendarRepo.upsertOverride(override)) {
                is Outcome.Ok -> {
                    syncScheduler.requestTargetRecompute()
                    _state.update { it.copy(deleted = true) }
                }
                is Outcome.Err -> _state.update { it.copy(saveError = UiMessage.of(Res.string.event_delete_occurrence_failed)) }
            }
        }
    }
}

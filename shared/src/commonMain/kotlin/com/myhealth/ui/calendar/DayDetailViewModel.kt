package com.myhealth.ui.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.engine.cycle.CycleEngine
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val KEY_DAY = "dayDetail.day"

/**
 * Backs [DayDetailScreen] (PLAN §4.2 Day detail, P3.5) — one [CalendarDay] observed through
 * [CalendarRepository.observeDay], plus the [LinkActivitySheet] flow added in P3.7.
 *
 * The shown day lives in the [SavedStateHandle] (seeded from the route argument), so the ±1-day
 * arrows in the top bar survive process death instead of snapping back to the route's day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayDetailViewModel(
    initialDay: Long,
    private val calendarRepo: CalendarRepository,
    private val planRepo: PlanRepository,
    private val syncScheduler: SyncScheduler,
    cycleRepo: CycleRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val dayFlow: StateFlow<Long> = savedState.getStateFlow(KEY_DAY, initialDay)
    private val pendingDelete = MutableStateFlow<Long?>(null)
    private val linkSheetOccurrence = MutableStateFlow<EventOccurrence?>(null)
    private val message = MutableStateFlow<UiMessage?>(null)

    private val dayData: Flow<CalendarDay> = dayFlow.flatMapLatest { day -> calendarRepo.observeDay(day) }
    private val proposals: Flow<List<LinkProposal>> =
        dayFlow.flatMapLatest { day -> calendarRepo.observeLinkProposals(day, day) }

    /** The "Cycle" line's source (P11.3): the day's status, computed straight from
     * [com.myhealth.domain.engine.cycle.CycleEngine] — reactive to both the shown day and the
     * logged entries, `null` while tracking is off or nothing is logged yet. */
    private data class DayCycle(val trackingEnabled: Boolean, val status: CycleStatus?)

    private val cycle: Flow<DayCycle> = dayFlow.flatMapLatest { day ->
        combine(cycleRepo.isTrackingEnabled(), cycleRepo.observeAll()) { enabled, entries ->
            DayCycle(enabled, if (enabled) CycleEngine.statusFor(day, entries) else null)
        }
    }

    private val extras: Flow<UiExtras> =
        combine(pendingDelete, linkSheetOccurrence, message) { pending, linkSheet, msg ->
            UiExtras(pending, linkSheet, msg)
        }

    val state: StateFlow<DayDetailUiState> =
        combine(dayFlow, dayData, proposals, extras, cycle) { day, data, props, extra, cyc ->
            DayDetailUiState(
                day = day,
                isLoading = false,
                data = data,
                pendingDeleteEventId = extra.pendingDelete,
                linkSheetOccurrence = extra.linkSheetOccurrence,
                linkProposals = props,
                message = extra.message,
                cycleTrackingEnabled = cyc.trackingEnabled,
                cycleStatus = cyc.status,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DayDetailUiState(day = dayFlow.value),
        )

    fun showPreviousDay() {
        savedState[KEY_DAY] = dayFlow.value - 1
    }

    fun showNextDay() {
        savedState[KEY_DAY] = dayFlow.value + 1
    }

    fun requestDeleteEvent(eventId: Long) {
        pendingDelete.value = eventId
    }

    fun cancelDeleteEvent() {
        pendingDelete.value = null
    }

    fun confirmDeleteEvent() {
        val eventId = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch {
            when (calendarRepo.deleteEvent(eventId)) {
                is Outcome.Ok -> {
                    // The day's DayType may have changed with it (P4.12).
                    syncScheduler.requestTargetRecompute()
                    message.value = UiMessage.of(Res.string.daydetail_msg_event_deleted)
                }
                is Outcome.Err -> message.value = UiMessage.of(Res.string.daydetail_msg_event_delete_failed)
            }
        }
    }

    fun setSessionStatus(sessionId: Long, status: PlannedStatus) {
        viewModelScope.launch {
            when (planRepo.setSessionStatus(sessionId, status)) {
                is Outcome.Ok -> {
                    // Completing or skipping a session changes the day's training energy (P4.12).
                    syncScheduler.requestTargetRecompute()
                    message.value = UiMessage.of(Res.string.daydetail_msg_session_marked, status.displayName().lowercase())
                }
                is Outcome.Err -> message.value = UiMessage.of(Res.string.daydetail_msg_session_update_failed)
            }
        }
    }

    /** Opens the [LinkActivitySheet] for this occurrence (P3.7). */
    fun openLinkSheet(occurrence: EventOccurrence) {
        linkSheetOccurrence.value = occurrence
    }

    fun closeLinkSheet() {
        linkSheetOccurrence.value = null
    }

    /** Selecting a candidate in the sheet — `AUTO_ACCEPTED` for a suggestion, `MANUAL` for the
     * fallback list; the repository upgrades a `SOCCER_MATCH` event's activity sport (P3.3/P3.7). */
    fun linkActivity(eventId: Long, activityId: Long, method: LinkMethod) {
        viewModelScope.launch {
            when (calendarRepo.linkActivity(eventId, activityId, method)) {
                is Outcome.Ok -> message.value = UiMessage.of(Res.string.daydetail_msg_activity_linked)
                is Outcome.Err -> message.value = UiMessage.of(Res.string.daydetail_msg_activity_link_failed)
            }
            linkSheetOccurrence.value = null
        }
    }

    fun unlinkActivity(eventId: Long) {
        viewModelScope.launch {
            when (calendarRepo.linkActivity(eventId, null, null)) {
                is Outcome.Ok -> message.value = UiMessage.of(Res.string.daydetail_msg_activity_unlinked)
                is Outcome.Err -> message.value = UiMessage.of(Res.string.daydetail_msg_activity_unlink_failed)
            }
            linkSheetOccurrence.value = null
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private data class UiExtras(
        val pendingDelete: Long?,
        val linkSheetOccurrence: EventOccurrence?,
        val message: UiMessage?,
    )
}

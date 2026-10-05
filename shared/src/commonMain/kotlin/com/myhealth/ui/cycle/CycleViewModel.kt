package com.myhealth.ui.cycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * Backs [CycleScreen] (PLAN §5 P11.3): the status card, "Log period start" / "Period ended" and
 * the history/forecast lists. All the forecasting math lives in
 * [com.myhealth.domain.engine.cycle.CycleEngine] behind [CycleRepository] — this class only wires
 * its flows together and turns two user actions into [CycleRepository.upsert] calls.
 */
class CycleViewModel(
    private val cycleRepo: CycleRepository,
    private val clock: PlatformClock,
) : ViewModel() {

    private val today: Long = clock.today().toEpochDays()

    /** Dialog visibility, the pending delete and the one-shot snackbar message — kept in one flow
     * so the four-flow `combine` below stays inside `kotlinx.coroutines.flow`'s typed overloads. */
    private data class Extras(
        val showLogStart: Boolean = false,
        val showPeriodEnded: Boolean = false,
        val pendingDeleteId: Long? = null,
        val message: UiMessage? = null,
    )

    private val extras = MutableStateFlow(Extras())

    val state: StateFlow<CycleUiState> = combine(
        cycleRepo.isTrackingEnabled(),
        cycleRepo.observeAll(),
        cycleRepo.observeStatus(today),
        cycleRepo.observeForecast(today),
        extras,
    ) { trackingEnabled, history, status, forecast, extra ->
        CycleUiState(
            isLoading = false,
            trackingEnabled = trackingEnabled,
            today = today,
            status = status,
            forecast = forecast,
            history = history,
            showLogStartDialog = extra.showLogStart,
            showPeriodEndedDialog = extra.showPeriodEnded,
            pendingDeleteId = extra.pendingDeleteId,
            message = extra.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleUiState(today = today))

    fun openLogStartDialog() {
        extras.update { it.copy(showLogStart = true) }
    }

    fun dismissLogStartDialog() {
        extras.update { it.copy(showLogStart = false) }
    }

    fun openPeriodEndedDialog() {
        extras.update { it.copy(showPeriodEnded = true) }
    }

    fun dismissPeriodEndedDialog() {
        extras.update { it.copy(showPeriodEnded = false) }
    }

    fun requestDelete(id: Long) {
        extras.update { it.copy(pendingDeleteId = id) }
    }

    fun cancelDelete() {
        extras.update { it.copy(pendingDeleteId = null) }
    }

    fun confirmDelete() {
        val id = extras.value.pendingDeleteId ?: return
        viewModelScope.launch {
            cycleRepo.delete(id)
            extras.update { it.copy(pendingDeleteId = null) }
        }
    }

    /**
     * "Log period start" (PLAN §5 P11.3). [RoomCycleRepository.upsert] would silently fold a
     * second tap on *today's own* start into an edit, but a start day that already belongs to a
     * *different* logged entry is a mistake the screen should refuse rather than quietly rewrite
     * history — so that check is made here, against the entries already loaded into [state].
     */
    fun logPeriodStart(day: Long) {
        viewModelScope.launch {
            val duplicate = state.value.history.any { it.periodStartDay == day }
            if (duplicate) {
                extras.update {
                    it.copy(showLogStart = false, message = UiMessage.of(Res.string.cycle_error_duplicate_start))
                }
                return@launch
            }
            val now = clock.millis()
            val entry = CycleEntry(periodStartDay = day, createdAtMillis = now, updatedAtMillis = now)
            val result = cycleRepo.upsert(entry)
            extras.update {
                it.copy(
                    showLogStart = false,
                    message = when (result) {
                        is Outcome.Ok -> UiMessage.of(Res.string.cycle_msg_period_logged)
                        is Outcome.Err -> UiMessage.of(Res.string.cycle_error_save_failed)
                    },
                )
            }
        }
    }

    /** "Period ended" (PLAN §5 P11.3): sets `periodEndDay` on the most recently started entry. */
    fun logPeriodEnded(day: Long) {
        viewModelScope.launch {
            val latest = state.value.latestEntry
            if (latest == null) {
                extras.update {
                    it.copy(showPeriodEnded = false, message = UiMessage.of(Res.string.cycle_error_no_period_to_end))
                }
                return@launch
            }
            val result = cycleRepo.upsert(latest.copy(periodEndDay = day, updatedAtMillis = clock.millis()))
            extras.update {
                it.copy(
                    showPeriodEnded = false,
                    message = when (result) {
                        is Outcome.Ok -> UiMessage.of(Res.string.cycle_msg_period_ended)
                        is Outcome.Err -> UiMessage.of(Res.string.cycle_error_period_end_before_start)
                    },
                )
            }
        }
    }

    fun consumeMessage() {
        extras.update { it.copy(message = null) }
    }
}

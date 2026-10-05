package com.myhealth.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.resources.*
import kotlinx.coroutines.flow.map
import com.myhealth.ui.zones.resolveHrZoneModel
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.zones.lightweightHrZoneModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** ViewModel state for [PlannedSessionEditScreen]. */
data class PlannedSessionEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val draft: PlannedSessionDraft = PlannedSessionDraft(),
    val errors: Map<PlannedSessionField, String> = emptyMap(),
    val isSaving: Boolean = false,
    val saveError: UiMessage? = null,
    val loadError: UiMessage? = null,
    val pendingDelete: Boolean = false,
    /** One-shot: the screen pops back once either flips to `true`. */
    val saved: Boolean = false,
    val deleted: Boolean = false,
    /** The loaded session's structure, kept only for display (P14.6, §4.2): the editor does not
     * offer to change it, so it is not part of [draft] and is unaffected by [save]. */
    val structureJson: String? = null,
    /** A profile-only zone model (§4.2) for the target-zone chip — see `lightweightHrZoneModel`. */
    val hrZoneModel: HrZoneModel? = null,
    /** Existing strength workouts, for the picker a `STRENGTH_*` session type offers (P14.7). */
    val workouts: List<StrengthWorkout> = emptyList(),
) {
    val sessionTypes: List<SessionType> get() = sessionTypesFor(draft.sportType)
}

/**
 * Backs [PlannedSessionEditScreen] (PLAN §4.2 "Planned session edit", P6.8). `id == -1` creates a
 * new session on `epochDay` (defaulting to today), attached to the active plan if there is one.
 *
 * Changing the sport re-derives the session type, and changing the session type re-derives the
 * intensity and the sport, so the three can never disagree with the §3.5.4 catalog — the same
 * consistency the suggester's own placements have.
 */
class PlannedSessionEditViewModel(
    private val id: Long,
    private val epochDay: Long,
    private val planRepo: PlanRepository,
    private val profileRepo: ProfileRepository,
    private val strengthRepo: StrengthRepository,
    private val clock: PlatformClock,
    private val healthRepo: HealthRepository? = null,
    private val activityRepo: ActivityRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PlannedSessionEditUiState(isLoading = id != NEW_ID, isNew = id == NEW_ID),
    )
    val state: StateFlow<PlannedSessionEditUiState> = _state.asStateFlow()

    init {
        load()
        profileRepo.observeProfile()
            .map { profile ->
                val today = clock.today()
                val health = healthRepo
                val activities = activityRepo
                if (health != null && activities != null) {
                    resolveHrZoneModel(profile, today.toEpochDays(), health, activities)
                } else {
                    lightweightHrZoneModel(profile, today)
                }
            }
            .onEach { model -> _state.update { it.copy(hrZoneModel = model) } }
            .launchIn(viewModelScope)
        strengthRepo.observeAll()
            .onEach { workouts -> _state.update { it.copy(workouts = workouts) } }
            .launchIn(viewModelScope)
    }

    private fun load() {
        viewModelScope.launch {
            if (id == NEW_ID) {
                val day = if (epochDay >= 0L) epochDay else clock.today().toEpochDays()
                val planId = planRepo.observeActivePlan().first()?.id
                _state.update {
                    it.copy(
                        isLoading = false,
                        draft = PlannedSessionDraft(
                            planId = planId,
                            day = LocalDate.fromEpochDays(day),
                        ),
                    )
                }
                return@launch
            }
            val session = planRepo.getSession(id)
            if (session == null) {
                _state.update {
                    it.copy(isLoading = false, loadError = UiMessage.of(Res.string.session_not_found))
                }
            } else {
                _state.update {
                    it.copy(isLoading = false, draft = plannedSessionDraftOf(session), structureJson = session.structureJson)
                }
            }
        }
    }

    fun update(transform: (PlannedSessionDraft) -> PlannedSessionDraft) {
        _state.update { current ->
            val draft = transform(current.draft)
            current.copy(
                draft = draft,
                errors = if (current.errors.isEmpty()) emptyMap() else validatePlannedSession(draft),
            )
        }
    }

    fun setSport(sportType: SportType) = update { draft ->
        val types = sessionTypesFor(sportType)
        val sessionType = if (draft.sessionType in types) draft.sessionType else types.first()
        draft.copy(
            sportType = sportType,
            sessionType = sessionType,
            intensity = intensityFor(sessionType, draft.intensity),
        )
    }

    fun setSessionType(sessionType: SessionType) = update { draft ->
        // P17.2: a workout only survives a session-type change within its own category — a
        // MOBILITY_* routine picked while the type was MOBILITY would otherwise dangle onto a
        // STRENGTH_* session (and vice versa), invisible in that type's now-filtered picker.
        val sameCategory = (draft.sessionType.isStrength() && sessionType.isStrength()) ||
            (draft.sessionType.isMobility() && sessionType.isMobility())
        draft.copy(
            sessionType = sessionType,
            sportType = sportTypeFor(sessionType, draft.sportType),
            intensity = intensityFor(sessionType, draft.intensity),
            workoutId = draft.workoutId.takeIf { sameCategory },
        )
    }

    /** The workout picker offered for a `STRENGTH_*` or `MOBILITY` session (§4.2 "Planned session
     * edit", P17.2). */
    fun setWorkout(workoutId: Long?) = update { it.copy(workoutId = workoutId) }

    fun save() {
        val draft = _state.value.draft
        val errors = validatePlannedSession(draft)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            when (planRepo.upsertSession(draft.toPlannedSession(clock))) {
                is Outcome.Ok -> _state.update { it.copy(isSaving = false, saved = true) }
                is Outcome.Err -> _state.update {
                    it.copy(isSaving = false, saveError = UiMessage.of(Res.string.session_save_error))
                }
            }
        }
    }

    fun requestDelete() = _state.update { it.copy(pendingDelete = true) }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = false) }

    fun confirmDelete() {
        _state.update { it.copy(pendingDelete = false) }
        viewModelScope.launch {
            when (planRepo.deleteSession(id)) {
                is Outcome.Ok -> _state.update { it.copy(deleted = true) }
                is Outcome.Err -> _state.update {
                    it.copy(saveError = UiMessage.of(Res.string.session_delete_error))
                }
            }
        }
    }

    private companion object {
        /** `PlannedSessionEditRoute`'s "no id" sentinel (§4.1). */
        const val NEW_ID = -1L
    }
}

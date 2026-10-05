package com.myhealth.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.engine.strength.EquipmentSetCodec
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock

/** ViewModel state for [WorkoutEditScreen] (PLAN §4.2 "Workout edit", P14.7). */
data class WorkoutEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val draft: WorkoutEditDraft = WorkoutEditDraft(),
    val validation: WorkoutValidation = WorkoutValidation(),
    val isSaving: Boolean = false,
    val saveError: UiMessage? = null,
    val loadError: UiMessage? = null,
    val saved: Boolean = false,
    /** `profile.availableEquipmentJson`, decoded (P16.2) — what [ExercisePickerSheet] filters its
     * results by. */
    val myEquipment: Set<Equipment>? = null,
) {
    val highlight: Map<MuscleGroup, Float> get() = draft.highlight()
}

/**
 * Backs [WorkoutEditScreen] (§4.2 "Workout edit", P14.7). `id == -1` creates a new, empty workout;
 * any other id loads it — including a built-in, which becomes an editable copy on save (`isBuiltIn`
 * stays whatever it already is; the repository does not special-case it beyond that).
 */
class WorkoutEditViewModel(
    private val id: Long,
    private val strengthRepo: StrengthRepository,
    private val profileRepo: ProfileRepository,
    /** `AppGraph.currentBodyWeightKg` (P16.2) — what [strengthRepo]'s `prescriptionFor` estimates
     * an unlogged exercise's load from. */
    private val bodyWeightKg: suspend () -> Double,
    private val clock: PlatformClock,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutEditUiState(isLoading = id != NEW_ID, isNew = id == NEW_ID))
    val state: StateFlow<WorkoutEditUiState> = _state.asStateFlow()

    init {
        if (id != NEW_ID) load()
        viewModelScope.launch {
            val equipment = EquipmentSetCodec.decode(profileRepo.getProfile()?.availableEquipmentJson)
            _state.update { it.copy(myEquipment = equipment) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val workout = strengthRepo.getById(id)
            if (workout == null) {
                _state.update { it.copy(isLoading = false, loadError = UiMessage.of(Res.string.workout_edit_load_error)) }
            } else {
                _state.update { it.copy(isLoading = false, draft = fillMissingLoads(workoutEditDraftOf(workout))) }
            }
        }
    }

    /**
     * Fills a `null` [WorkoutExerciseDraft.loadKg] from `prescriptionFor` (P16.2): the one field an
     * `ExerciseSubstitution` row loses when a template is materialised against "my equipment"
     * (§P16 "a substitute is never prescribed with the original's load"). Bodyweight rows and rows
     * that already carry a load skip the lookup.
     */
    private suspend fun fillMissingLoads(draft: WorkoutEditDraft): WorkoutEditDraft {
        if (draft.exercises.none { it.loadKg == null && !it.isBodyweight }) return draft
        val weight = bodyWeightKg()
        val rows = draft.exercises.map { row ->
            if (row.loadKg != null || row.isBodyweight) return@map row
            val exercise = ExerciseCatalog.byId(row.exerciseId) ?: return@map row
            row.withPrescription(strengthRepo.prescriptionFor(exercise, weight))
        }
        return draft.copy(exercises = rows)
    }

    fun update(transform: (WorkoutEditDraft) -> WorkoutEditDraft) {
        _state.update { current ->
            val draft = transform(current.draft)
            current.copy(draft = draft, validation = validateWorkoutDraft(draft))
        }
    }

    fun setName(name: String) = update { it.copy(name = name) }

    fun setKind(kind: StrengthWorkoutKind) = update { it.copy(kind = kind) }

    fun setNotes(notes: String) = update { it.copy(notes = notes) }

    /**
     * Appends [exerciseId] with `WorkoutEditDraft.addExercise`'s generic placeholder immediately
     * (10 reps / a 30 s hold, no load) for instant feedback, then replaces it with the real
     * prescription once `strengthRepo.prescriptionFor` resolves (P16.2's "prefill load/reps … when
     * the row has none" — right after adding, a row's numbers are a placeholder, not a real one).
     */
    fun addExercise(exerciseId: String) {
        val exercise = ExerciseCatalog.byId(exerciseId) ?: return
        update { it.addExercise(exerciseId) }
        viewModelScope.launch {
            val prescription = strengthRepo.prescriptionFor(exercise, bodyWeightKg())
            update { draft ->
                val rows = draft.exercises.toMutableList()
                val index = rows.indexOfLast { it.exerciseId == exerciseId }
                if (index == -1) return@update draft
                rows[index] = rows[index].copy(reps = null, seconds = null).withPrescription(prescription)
                draft.copy(exercises = rows)
            }
        }
    }

    fun removeExercise(index: Int) = update { it.removeExercise(index) }

    fun moveUp(index: Int) = update { it.moveExercise(index, index - 1) }

    fun moveDown(index: Int) = update { it.moveExercise(index, index + 1) }

    fun updateRow(index: Int, transform: (WorkoutExerciseDraft) -> WorkoutExerciseDraft) = update { draft ->
        val rows = draft.exercises.toMutableList()
        if (index in rows.indices) rows[index] = transform(rows[index])
        draft.copy(exercises = rows)
    }

    fun save() {
        val draft = _state.value.draft
        val validation = validateWorkoutDraft(draft)
        if (!validation.isValid) {
            _state.update { it.copy(validation = validation) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            when (strengthRepo.upsertWorkout(draft.toStrengthWorkout(clock))) {
                is Outcome.Ok -> _state.update { it.copy(isSaving = false, saved = true) }
                is Outcome.Err -> _state.update {
                    it.copy(isSaving = false, saveError = UiMessage.of(Res.string.workout_edit_save_error))
                }
            }
        }
    }

    private companion object {
        /** `WorkoutEditRoute`'s "no id" sentinel (§4.1). */
        const val NEW_ID = -1L
    }
}

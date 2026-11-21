package com.myhealth.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.PlatformClock
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.UserExerciseIds
import com.myhealth.domain.repository.UserExerciseRepository
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [ExerciseEditScreen] (P26.2): creates or edits one of the owner's own exercises. A new
 * exercise may start with a name ([prefillName]) — the video import screen passes the pasted line.
 */
class ExerciseEditViewModel(
    private val repo: UserExerciseRepository,
    private val clock: PlatformClock,
    private val exerciseId: String?,
    private val prefillName: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseEditUiState())
    val state: StateFlow<ExerciseEditUiState> = _state.asStateFlow()

    private var library: ExerciseLibrary = ExerciseLibrary.CATALOG_ONLY

    /** The id of the exercise once saved, or `null` once deleted; the screen then leaves. */
    private val _done = Channel<Result>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    sealed interface Result {
        data class Saved(val id: String) : Result
        data object Deleted : Result
    }

    init {
        viewModelScope.launch {
            library = repo.getLibrary()
            val existing = exerciseId?.let { library.userExercise(it) }
            val draft = existing?.let { ExerciseEditDraft.of(it) }
                ?: ExerciseEditDraft(name = prefillName?.trim().orEmpty())
            val usage = existing?.let { repo.usageCount(it.exercise.id) } ?: 0
            _state.value = ExerciseEditUiState(
                draft = draft,
                loading = false,
                usageCount = usage,
                duplicate = draft.duplicateIn(library),
            )
        }
    }

    fun update(change: (ExerciseEditDraft) -> ExerciseEditDraft) = _state.update { current ->
        val draft = change(current.draft)
        current.copy(draft = draft, duplicate = draft.duplicateIn(library))
    }

    fun save() {
        val current = _state.value
        if (!current.draft.canSave) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        if (current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val exercise = current.draft.toUserExercise({ UserExerciseIds.next() }, clock.millis())
            when (repo.upsert(exercise)) {
                is Outcome.Ok -> _done.send(Result.Saved(exercise.exercise.id))
                is Outcome.Err -> _state.update { it.copy(saving = false, showErrors = true) }
            }
        }
    }

    fun setArchived(archived: Boolean) {
        val id = _state.value.draft.id ?: return
        viewModelScope.launch {
            if (repo.setArchived(id, archived) is Outcome.Ok) {
                _state.update { it.copy(draft = it.draft.copy(archived = archived)) }
            }
        }
    }

    fun delete() {
        val id = _state.value.draft.id ?: return
        viewModelScope.launch {
            if (repo.usageCount(id) > 0) return@launch
            if (repo.delete(id) is Outcome.Ok) _done.send(Result.Deleted)
        }
    }
}

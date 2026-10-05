package com.myhealth.ui.ingredients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.di.OffLookup
import com.myhealth.di.ScanMessages
import com.myhealth.domain.engine.nutrition.NutritionValidator
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.util.EngineWarning
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.camera.DraftStore
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock

/**
 * Backs [IngredientEditScreen] (PLAN §4.2 Ingredient edit, P4.3, P4.9/P4.10).
 *
 * `id == -1L` creates a new ingredient, and a scan reaches it two ways: [draftStore] carries an
 * accepted OCR review or a barcode lookup from the camera screen (consumed once, on start, so a
 * later unrelated visit never sees a stale scan), while [lookUpBarcode] runs the same Open Food
 * Facts lookup for a barcode the user typed in by hand. Either way the form is only *prefilled* —
 * the user still has to press Save (brief §2.1, risk R9).
 */
class IngredientEditViewModel(
    private val id: Long,
    private val barcode: String?,
    private val ingredientRepo: IngredientRepository,
    private val clock: PlatformClock,
    private val draftStore: DraftStore,
    private val offLookup: OffLookup,
) : ViewModel() {

    private val _state = MutableStateFlow(IngredientEditUiState(isLoading = id != -1L, isNew = id == -1L))
    val state: StateFlow<IngredientEditUiState> = _state.asStateFlow()

    init {
        // Always consumed, never left behind: a scan belongs to the editor opening right now.
        val scan = draftStore.take()
        if (id == -1L) {
            val fresh = newIngredientDraft(barcode)
            val draft = if (scan == null) fresh else fresh.withScan(scan)
            _state.update {
                it.copy(
                    isLoading = false,
                    draft = draft,
                    warnings = warningsFor(draft),
                    scanWarnings = scan?.warnings.orEmpty(),
                )
            }
        } else {
            load()
        }
    }

    /**
     * Looks the barcode currently in the form up on Open Food Facts and prefills what it returns
     * (P4.10, the manual-entry path). Fields OFF does not know are left untouched.
     */
    fun lookUpBarcode() {
        val code = _state.value.draft.barcode.trim()
        if (code.isBlank()) {
            _state.update { it.copy(lookupError = "Enter or scan a barcode first.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLookingUp = true, lookupError = null, lookupNote = null) }
            when (val result = offLookup.lookup(code)) {
                is Outcome.Ok -> _state.update { current ->
                    val draft = current.draft.withScan(result.value)
                    current.copy(
                        isLookingUp = false,
                        draft = draft,
                        errors = validate(draft),
                        warnings = warningsFor(draft),
                        lookupNote = UiMessage.of(Res.string.ingredient_lookup_note_filled),
                    )
                }
                is Outcome.Err -> _state.update {
                    it.copy(isLookingUp = false, lookupError = ScanMessages.of(result.error))
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val ingredient = ingredientRepo.getById(id)
            if (ingredient == null) {
                _state.update { it.copy(isLoading = false, loadError = UiMessage.of(Res.string.ingredient_edit_load_error)) }
                return@launch
            }
            val draft = fromIngredient(ingredient)
            _state.update { it.copy(isLoading = false, draft = draft, warnings = warningsFor(draft)) }
        }
    }

    fun updateDraft(transform: (IngredientDraft) -> IngredientDraft) {
        _state.update {
            val draft = transform(it.draft)
            it.copy(draft = draft, errors = validate(draft), warnings = warningsFor(draft))
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
            when (ingredientRepo.upsert(current.draft.toIngredient(clock))) {
                is Outcome.Ok -> _state.update { it.copy(isSaving = false, saved = true) }
                is Outcome.Err -> _state.update {
                    it.copy(isSaving = false, saveError = UiMessage.of(Res.string.ingredient_edit_save_error))
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

    fun confirmDelete() {
        _state.update { it.copy(pendingDelete = false) }
        viewModelScope.launch {
            when (ingredientRepo.deleteOrArchive(id)) {
                is Outcome.Ok -> _state.update { it.copy(deleted = true) }
                is Outcome.Err -> _state.update { it.copy(saveError = UiMessage.of(Res.string.ingredient_edit_delete_error)) }
            }
        }
    }

    /**
     * Plausibility warnings for the current form — empty while no nutrient field has been filled
     * in at all, so a brand-new editor does not open on "No energy value was found." (POLISH-1).
     */
    private fun warningsFor(draft: IngredientDraft): List<EngineWarning> =
        if (draft.hasAnyNutrient()) NutritionValidator.validate(draft.toNutritionFacts()) else emptyList()
}

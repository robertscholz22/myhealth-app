package com.myhealth.ui.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock

private const val PICKER_LIMIT = 50
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Backs [MealTemplateEditScreen] (PLAN §4.2 Meal template edit, P4.4). `id == -1L` creates a new
 * template. The ingredient picker is a debounced (250 ms) [IngredientRepository.search], collected
 * only while the sheet is open.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MealTemplateEditViewModel(
    private val id: Long,
    private val mealRepo: MealRepository,
    private val ingredientRepo: IngredientRepository,
    private val clock: PlatformClock,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MealTemplateEditUiState(isLoading = id != -1L, isNew = id == -1L),
    )
    val state: StateFlow<MealTemplateEditUiState> = _state.asStateFlow()

    private val pickerQuery = MutableStateFlow("")
    private var pickerJob: Job? = null

    init {
        if (id == -1L) {
            _state.update { it.copy(isLoading = false) }
        } else {
            load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            val template = mealRepo.getTemplate(id)
            if (template == null) {
                _state.update { it.copy(isLoading = false, loadError = UiMessage.of(Res.string.mealtpl_error_not_found)) }
                return@launch
            }
            val ingredients = ingredientRepo
                .getByIds(template.items.map { it.ingredientId }.distinct())
                .associateBy { it.id }
            val draft = templateDraftOf(template, ingredients)
            _state.update { it.copy(isLoading = false, draft = draft, totals = draft.totals()) }
        }
    }

    // ---- form ------------------------------------------------------------------------------------

    fun setName(value: String) = updateDraft { it.copy(name = value) }

    fun setDefaultSlot(value: MealSlot?) = updateDraft { it.copy(defaultSlot = value) }

    fun setNote(value: String) = updateDraft { it.copy(note = value) }

    fun setFavorite(value: Boolean) = updateDraft { it.copy(isFavorite = value) }

    fun setItemQuantity(index: Int, quantity: Double?) = updateItem(index) { it.copy(quantity = quantity) }

    fun setItemUnit(index: Int, unit: QuantityUnit) = updateItem(index) { it.copy(unit = unit) }

    fun removeItem(index: Int) = updateDraft { draft ->
        draft.copy(items = draft.items.filterIndexed { i, _ -> i != index })
    }

    fun addIngredient(ingredient: Ingredient) {
        closePicker()
        updateDraft { draft -> draft.copy(items = draft.items + itemDraftFor(ingredient)) }
    }

    // ---- ingredient picker ------------------------------------------------------------------------

    fun openPicker() {
        _state.update { it.copy(pickerOpen = true, pickerQuery = "", pickerResults = emptyList()) }
        pickerQuery.value = ""
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            pickerQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged()
                .flatMapLatest { query -> ingredientRepo.search(query, PICKER_LIMIT) }
                .collectLatest { results -> _state.update { it.copy(pickerResults = results) } }
        }
    }

    fun setPickerQuery(value: String) {
        pickerQuery.value = value
        _state.update { it.copy(pickerQuery = value) }
    }

    fun closePicker() {
        pickerJob?.cancel()
        pickerJob = null
        _state.update { it.copy(pickerOpen = false, pickerQuery = "", pickerResults = emptyList()) }
    }

    // ---- save / delete ----------------------------------------------------------------------------

    fun save() {
        val current = _state.value.draft
        val errors = validateTemplate(current)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            when (mealRepo.upsertTemplate(current.toMealTemplate(clock))) {
                is Outcome.Ok -> _state.update { it.copy(isSaving = false, saved = true) }
                is Outcome.Err -> _state.update {
                    it.copy(isSaving = false, saveError = UiMessage.of(Res.string.mealtpl_error_save_failed))
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
            when (mealRepo.deleteTemplate(id)) {
                is Outcome.Ok -> _state.update { it.copy(deleted = true) }
                is Outcome.Err -> _state.update {
                    it.copy(saveError = UiMessage.of(Res.string.mealtpl_error_delete_failed))
                }
            }
        }
    }

    private fun updateItem(index: Int, transform: (TemplateItemDraft) -> TemplateItemDraft) =
        updateDraft { draft ->
            draft.copy(items = draft.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
        }

    private fun updateDraft(transform: (MealTemplateDraft) -> MealTemplateDraft) {
        _state.update { current ->
            val draft = transform(current.draft)
            current.copy(draft = draft, totals = draft.totals(), errors = validateTemplate(draft))
        }
    }
}

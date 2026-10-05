package com.myhealth.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.MealItemInput
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LIST_LIMIT = 100
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Backs [AddFoodScreen] (PLAN §4.2 Add food, P4.6): the Recents/Favorites/Search/Templates lists
 * and the [QuantityEditor] that turns a picked ingredient into a logged item.
 *
 * Search is debounced 250 ms in the ViewModel (the repository's `search` is deliberately
 * undebounced — P4.2). "Add" is a one-item [MealRepository.logMeal], which is what snapshots the
 * nutrients (§2.2.5).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AddFoodViewModel(
    private val day: Long,
    private val slot: MealSlot,
    private val mealRepo: MealRepository,
    private val ingredientRepo: IngredientRepository,
) : ViewModel() {

    private val tab = MutableStateFlow(AddFoodTab.RECENTS)
    private val query = MutableStateFlow("")
    private val editor = MutableStateFlow(Editor())

    private val ingredients: Flow<List<Ingredient>> = combine(
        tab,
        query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
    ) { current, q -> current to q }
        .flatMapLatest { (current, q) ->
            when (current) {
                AddFoodTab.RECENTS -> ingredientRepo.observeRecent(LIST_LIMIT)
                AddFoodTab.FAVORITES -> ingredientRepo.observeFavorites()
                AddFoodTab.SEARCH -> ingredientRepo.search(q, LIST_LIMIT)
                AddFoodTab.TEMPLATES, AddFoodTab.SCAN -> flowOf(emptyList())
            }
        }

    private val templates: Flow<List<MealTemplate>> = tab.flatMapLatest { current ->
        if (current == AddFoodTab.TEMPLATES) mealRepo.observeTemplates() else flowOf(emptyList())
    }

    val state: StateFlow<AddFoodUiState> =
        combine(tab, query, ingredients, templates, editor) { current, q, list, tpl, edit ->
            AddFoodUiState(
                day = day,
                slot = slot,
                tab = current,
                query = q,
                ingredients = list,
                templates = tpl,
                selected = edit.selected,
                quantity = edit.quantity,
                unit = edit.unit,
                isSaving = edit.isSaving,
                message = edit.message,
                added = edit.added,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AddFoodUiState(day = day, slot = slot),
        )

    fun setTab(value: AddFoodTab) {
        tab.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Opens the quantity editor pre-filled with the ingredient's natural unit and amount. */
    fun select(ingredient: Ingredient) {
        val unit = defaultUnitFor(ingredient)
        editor.value = Editor(
            selected = ingredient,
            quantity = when (unit) {
                QuantityUnit.G, QuantityUnit.ML -> 100.0
                QuantityUnit.PIECE, QuantityUnit.SERVING -> 1.0
            },
            unit = unit,
        )
    }

    fun setQuantity(value: Double?) {
        editor.update { it.copy(quantity = value) }
    }

    fun setUnit(value: QuantityUnit) {
        editor.update { it.copy(unit = value) }
    }

    fun applyChip(chip: QuickChip) {
        editor.update { it.copy(quantity = chip.quantity, unit = chip.unit) }
    }

    fun cancelSelection() {
        editor.value = Editor()
    }

    fun add() {
        val current = editor.value
        val ingredient = current.selected ?: return
        val quantity = current.quantity
        if (quantity == null || quantity <= 0.0) {
            editor.update { it.copy(message = UiMessage.of(Res.string.quantity_error_zero)) }
            return
        }
        viewModelScope.launch {
            editor.update { it.copy(isSaving = true, message = null) }
            val result = mealRepo.logMeal(
                day = day,
                slot = slot,
                items = listOf(MealItemInput(ingredient.id, quantity, current.unit)),
                name = ingredient.name,
            )
            editor.update {
                when (result) {
                    is Outcome.Ok -> it.copy(isSaving = false, added = true)
                    is Outcome.Err -> it.copy(isSaving = false, message = UiMessage.of(Res.string.addfood_error_add_failed))
                }
            }
        }
    }

    /** Templates tab: one tap logs the whole template into this day and slot (§4.2, P4.4). */
    fun logTemplate(templateId: Long) {
        viewModelScope.launch {
            editor.update { it.copy(isSaving = true, message = null) }
            val result = mealRepo.logTemplate(templateId, day, slot)
            editor.update {
                when (result) {
                    is Outcome.Ok -> it.copy(isSaving = false, added = true)
                    is Outcome.Err ->
                        it.copy(isSaving = false, message = UiMessage.of(Res.string.addfood_error_log_template_failed))
                }
            }
        }
    }

    fun consumeMessage() {
        editor.update { it.copy(message = null) }
    }

    private data class Editor(
        val selected: Ingredient? = null,
        val quantity: Double? = null,
        val unit: QuantityUnit = QuantityUnit.G,
        val isSaving: Boolean = false,
        val message: UiMessage? = null,
        val added: Boolean = false,
    )
}

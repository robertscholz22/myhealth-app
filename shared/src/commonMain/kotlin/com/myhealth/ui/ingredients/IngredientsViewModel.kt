package com.myhealth.ui.ingredients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.repository.IngredientRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val LIST_LIMIT = 200
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Backs [IngredientsScreen] (PLAN §4.2 Ingredients, P4.3): a debounced search over the active
 * ingredients, or the archived list when [showArchived] is on (client-side filtered, since
 * archived rows are a small side list rather than the main searchable catalogue — §2.2.5).
 */
class IngredientsViewModel(private val ingredientRepo: IngredientRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val items = combine(
        query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
        showArchived,
    ) { q, archived -> q to archived }
        .flatMapLatest { (q, archived) ->
            if (archived) {
                ingredientRepo.observeArchived(LIST_LIMIT).map { list ->
                    list.filter { it.name.contains(q, ignoreCase = true) || it.brand?.contains(q, ignoreCase = true) == true }
                }
            } else {
                ingredientRepo.search(q, LIST_LIMIT)
            }
        }

    val state: StateFlow<IngredientsUiState> = combine(query, showArchived, items) { q, archived, list ->
        IngredientsUiState(isLoading = false, query = q, showArchived = archived, items = list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IngredientsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setShowArchived(value: Boolean) {
        showArchived.value = value
    }

    fun toggleFavorite(id: Long, favorite: Boolean) {
        viewModelScope.launch { ingredientRepo.setFavorite(id, favorite) }
    }
}

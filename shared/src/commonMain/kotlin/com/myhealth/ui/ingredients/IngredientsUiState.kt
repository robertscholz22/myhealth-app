package com.myhealth.ui.ingredients

import com.myhealth.domain.model.Ingredient

/** ViewModel state for [IngredientsScreen] (PLAN §4.2 Ingredients, P4.3). */
data class IngredientsUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val showArchived: Boolean = false,
    val items: List<Ingredient> = emptyList(),
)

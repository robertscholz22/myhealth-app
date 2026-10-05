package com.myhealth.ui.meals

import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.ui.common.UiMessage

/** ViewModel state for [MealTemplateEditScreen] (PLAN §4.2 Meal template edit, P4.4). */
data class MealTemplateEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val draft: MealTemplateDraft = MealTemplateDraft(),
    val errors: Map<TemplateField, String> = emptyMap(),
    val totals: MacroTotals = MacroTotals.ZERO,
    /** Ingredient-picker bottom sheet (§4.2: "item rows (ingredient + qty + unit)"). */
    val pickerOpen: Boolean = false,
    val pickerQuery: String = "",
    val pickerResults: List<Ingredient> = emptyList(),
    val pendingDelete: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: UiMessage? = null,
    val loadError: UiMessage? = null,
    /** One-shot: the screen pops back once either flips to `true`. */
    val saved: Boolean = false,
    val deleted: Boolean = false,
)
